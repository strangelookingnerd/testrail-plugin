/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 *     http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jenkinsci.plugins.testrail;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.testrail.JUnit.Failure;
import org.jenkinsci.plugins.testrail.JUnit.JUnitResults;
import org.jenkinsci.plugins.testrail.JUnit.TestCase;
import org.jenkinsci.plugins.testrail.JUnit.TestSuite;
import org.jenkinsci.plugins.testrail.TestRail.*;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.xml.bind.JAXBException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import static org.jenkinsci.plugins.testrail.Utils.log;

public class TestRailNotifier extends Notifier implements SimpleBuildStep {

    private int testrailProject;
    private int testrailSuite;
    private String junitResultsGlob;
    private String testrailMilestone;
    private boolean enableMilestone;
    private boolean createNewTestcases;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public TestRailNotifier(int testrailProject, int testrailSuite, String junitResultsGlob, String testrailMilestone, boolean enableMilestone, boolean createNewTestcases) {
        this.testrailProject = testrailProject;
        this.testrailSuite = testrailSuite;
        this.junitResultsGlob = junitResultsGlob;
        this.testrailMilestone = testrailMilestone;
        this.enableMilestone = enableMilestone;
        this.createNewTestcases = createNewTestcases;
    }

    @DataBoundSetter
    public void setTestrailProject(int project) { this.testrailProject = project;}
    public int getTestrailProject() { return this.testrailProject; }
    @DataBoundSetter
    public void setTestrailSuite(int suite) { this.testrailSuite = suite; }
    public int getTestrailSuite() { return this.testrailSuite; }
    @DataBoundSetter
    public void setJunitResultsGlob(String glob) { this.junitResultsGlob = glob; }
    public String getJunitResultsGlob() { return this.junitResultsGlob; }
    public String getTestrailMilestone() { return this.testrailMilestone; }
    @DataBoundSetter
    public void setTestrailMilestone(String milestone) { this.testrailMilestone = milestone; }
    @DataBoundSetter
    public void setEnableMilestone(boolean mstone) {this.enableMilestone = mstone; }
    public boolean getEnableMilestone() { return  this.enableMilestone; }
    @DataBoundSetter
    public void setCreateNewTestcases(boolean newcases) {this.createNewTestcases = newcases; }
    public boolean getCreateNewTestcases() { return  this.createNewTestcases; }


    @Override
    public void perform(@Nonnull hudson.model.Run<?, ?> run, @Nonnull FilePath workspace, @Nonnull EnvVars envVars, @Nonnull Launcher launcher, @Nonnull TaskListener taskListener) throws InterruptedException, IOException {
        TestRailClient  testrail = getDescriptor().getTestrailInstance();
        testrail.setHost(getDescriptor().getTestrailHost());
        testrail.setUser(getDescriptor().getTestrailUser());
        testrail.setPassword(getDescriptor().getTestrailPassword());

        ExistingTestCases testCases;
        try {
            testCases = new ExistingTestCases(testrail, this.testrailProject, this.testrailSuite);
        } catch (ElementNotFoundException e) {
            taskListener.getLogger().println("Cannot find project or suite on TestRail server. Please check your Jenkins job and system configurations.");
            run.setResult(hudson.model.Result.FAILURE);
            return;
        }

        String[] caseNames;
        try {
            caseNames = testCases.listTestCases();
            taskListener.getLogger().println("Test Cases: ");
            for (String caseName : caseNames) {
                taskListener.getLogger().println("  " + caseName);
            }
        } catch (ElementNotFoundException e) {
            taskListener.getLogger().println("Failed to list test cases");
            taskListener.getLogger().println("Element not found:" + e.getMessage());
        }

        taskListener.getLogger().println("Munging test result files.");
        TestRailResults results = new TestRailResults();

        // FilePath doesn't have a read method. We want to actually process the files on the master
        // because during processing we talk to TestRail and slaves might not be able to.
        // So we'll copy the result files to the master and munge them there:
        //
        // Create a temp directory.
        // Do a base.copyRecursiveTo() with file masks into the temp dir.
        // process the temp files.
        // it looks like the destructor deletes the temp dir when we're finished
        FilePath tempdir = new FilePath(Util.createTempDir());
        // This picks up *all* result files so if you have old results in the same directory we'll see those, too.
        try {
            workspace.copyRecursiveTo(junitResultsGlob, "", tempdir);
        } catch (Exception e) {
            taskListener.getLogger().println("Error trying to copy files to Jenkins master: " + e.getMessage());
            run.setResult(hudson.model.Result.FAILURE);
        }

        JUnitResults actualJunitResults;

        try {
            actualJunitResults = new JUnitResults(tempdir, this.junitResultsGlob, taskListener.getLogger());
        } catch (JAXBException e) {
            log(e.getMessage());
            run.setResult(hudson.model.Result.FAILURE);
            return;
        }

        List<TestSuite> suites = actualJunitResults.getSuites();
        try {
            for (TestSuite suite: suites) {
                results.merge(addSuite(suite, null, testCases));
            }
        } catch (Exception e) {
            taskListener.getLogger().println("Failed to create missing Test Suites in TestRail.");
            taskListener.getLogger().println("EXCEPTION: " + e.getMessage());
        }

        taskListener.getLogger().println("Uploading results to TestRail.");
        String runComment = "Automated results from Jenkins: " + workspace.toURI();
        String milestoneId = testrailMilestone;

        int runId = -1;
        TestRailResponse response = null;
        try {
            runId = testrail.addRun(testCases.getProjectId(), testCases.getSuiteId(), milestoneId, runComment);
            response = testrail.addResultsForCases(runId, results);
        } catch (TestRailException e) {
            taskListener.getLogger().println("Error pushing results to TestRail");
            taskListener.getLogger().println(e.getMessage());
            run.setResult(hudson.model.Result.FAILURE);
        }

        boolean buildResult = (response != null && response.getStatus() == 200);

        if (buildResult) {
            taskListener.getLogger().println("Successfully uploaded test results.");
        } else {
            taskListener.getLogger().println("Failed to add results to TestRail.");
            taskListener.getLogger().println("status: " + ((response != null) ? response.getStatus() : -1));
            taskListener.getLogger().println("body :\n" + ((response != null) ? response.getBody() : "NULL"));
        }

        try {
            testrail.closeRun(runId);
        } catch (Exception e) {
            taskListener.getLogger().println("Failed to close test run in TestRail.");
            taskListener.getLogger().println("EXCEPTION: " + e.getMessage());
        }
    }

    public TestRailResults addSuite(TestSuite suite, String parentId, ExistingTestCases existingCases) throws IOException, TestRailException {
        //figure out TR sectionID
        int sectionId;
        try {
            sectionId = existingCases.getSectionId(suite.getName());
        } catch (ElementNotFoundException e1) {
            try {
                sectionId = existingCases.addSection(suite.getName(), parentId);
            } catch (ElementNotFoundException e) {
                log("Unable to add test section " + suite.getName());
                log(e.getMessage());
                return null;
            }
        }

        //if we have any subsections - process them
        TestRailResults results = new TestRailResults();

        if (suite.hasSuites()) {
            for (TestSuite subsuite : suite.getSuites()) {
                results.merge(addSuite(subsuite, String.valueOf(sectionId), existingCases));
            }
        }

        if (suite.hasCases()) {
            for (TestCase testcase : suite.getCases()) {
                int caseId = 0;
                boolean addResult = false;
                try {
                    caseId = existingCases.getCaseId(suite.getName(), testcase.getName());
                    addResult = true;
                } catch (ElementNotFoundException e) {
                    if (this.createNewTestcases) {
                        caseId = existingCases.addCase(testcase, sectionId);
                        addResult = true;
                    }
                }
                if (addResult) {
	                CaseStatus caseStatus;
	                Float caseTime = testcase.getTime();
	                String caseComment = null;
	                Failure caseFailure = testcase.getFailure();
	                if (caseFailure != null) {
	                    caseStatus = CaseStatus.FAILED;
	                    caseComment = (caseFailure.getMessage() == null) ? caseFailure.getText() : caseFailure.getMessage() + "\n" + caseFailure.getText();
	                } else if (testcase.getSkipped() != null) {
	                    caseStatus = CaseStatus.UNTESTED;
	                } else {
	                    caseStatus = CaseStatus.PASSED;
	                }

	                if (caseStatus != CaseStatus.UNTESTED){
	                    results.addResult(new TestRailResult(caseId, caseStatus, caseComment, caseTime));
	                }
	            }
            }
        }

        return results;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE; //null;
    }

    @Symbol("testRail")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        private String testrailHost = "";
        private String testrailUser = "";
        private String testrailPassword = "";
        private TestRailClient testrail = new TestRailClient("", "", "");

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckTestrailProject(@QueryParameter int value)
                throws IOException {
            testrail.setHost(getTestrailHost());
            testrail.setUser(getTestrailUser());
            testrail.setPassword(getTestrailPassword());
            if (getTestrailHost().isEmpty() || getTestrailUser().isEmpty() || getTestrailPassword().isEmpty() || !testrail.serverReachable() || !testrail.authenticationWorks()) {
                return FormValidation.warning("Please fix your TestRail configuration in Manage Jenkins -> Configure System.");
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillTestrailProjectItems() {
            testrail.setHost(getTestrailHost());
            testrail.setUser(getTestrailUser());
            testrail.setPassword(getTestrailPassword());

            ListBoxModel items = new ListBoxModel();
            try {
                for (Project prj : testrail.getProjects()) {
                    items.add(prj.getName(), prj.getStringId());
                }
            } catch (ElementNotFoundException | IOException ignored) {
            }

            return items;
        }

        public ListBoxModel doFillTestrailSuiteItems(@QueryParameter int testrailProject) {
            testrail.setHost(getTestrailHost());
            testrail.setUser(getTestrailUser());
            testrail.setPassword(getTestrailPassword());

            ListBoxModel items = new ListBoxModel();
            try {
                for (Suite suite : testrail.getSuites(testrailProject)) {
                    items.add(suite.getName(), suite.getStringId());
                }
            } catch (ElementNotFoundException | IOException ignored) {
            }

            return items;
        }

        public FormValidation doCheckTestrailSuite(@QueryParameter String value)
                throws IOException {
            testrail.setHost(getTestrailHost());
            testrail.setUser(getTestrailUser());
            testrail.setPassword(getTestrailPassword());

            if (getTestrailHost().isEmpty() || getTestrailUser().isEmpty() || getTestrailPassword().isEmpty() || !testrail.serverReachable() || !testrail.authenticationWorks()) {
                return FormValidation.warning("Please fix your TestRail configuration in Manage Jenkins -> Configure System.");
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckJunitResultsGlob(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.warning("Please select test result path.");
            }
            // TODO: Should we check to see if the files exist? Probably not.
            return FormValidation.ok();
        }

        public FormValidation doCheckTestrailHost(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.warning("Please add your TestRail host URI.");
            }
            try {
                new URI(value);
            } catch (URISyntaxException e) {
                return FormValidation.error("Host must be a valid URI");
            }
            testrail.setHost(value);
            testrail.setUser("");
            testrail.setPassword("");
            if (!testrail.serverReachable()) {
                return FormValidation.error("Host is not reachable.");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTestrailUser(@QueryParameter String value,
                                                  @QueryParameter String testrailHost,
                                                  @QueryParameter String testrailPassword)
                throws IOException {
            if (value.isEmpty()) {
                return FormValidation.warning("Please add your user's email address.");
            }
            if (!testrailPassword.isEmpty()) {
                testrail.setHost(testrailHost);
                testrail.setUser(value);
                testrail.setPassword(testrailPassword);
                if (testrail.serverReachable() && !testrail.authenticationWorks()){
                    return FormValidation.error("Invalid user/password combination.");
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckTestrailPassword(@QueryParameter String value,
                                                      @QueryParameter String testrailHost,
                                                      @QueryParameter String testrailUser)
                throws IOException {
            if (value.isEmpty()) {
                return FormValidation.warning("Please add your password.");
            }
            if (!testrailUser.isEmpty()) {
                testrail.setHost(testrailHost);
                testrail.setUser(testrailUser);
                testrail.setPassword(value);
                if (testrail.serverReachable() && !testrail.authenticationWorks()){
                    return FormValidation.error("Invalid user/password combination.");
                }
            }
            return FormValidation.ok();
        }

        public ListBoxModel doFillTestrailMilestoneItems(@QueryParameter int testrailProject) {
            ListBoxModel items = new ListBoxModel();
            items.add("None", "");
            try {
                for (Milestone mstone : testrail.getMilestones(testrailProject)) {
                    items.add(mstone.getName(), mstone.getId());
                }
            } catch (ElementNotFoundException | IOException ignored) {
            }
            return items;
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human-readable name is used in the configuration screen.
         */
        @NonNull
        public String getDisplayName() {
            return "TestRail Plugin";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().
            testrailHost = formData.getString("testrailHost");
            testrailUser = formData.getString("testrailUser");
            testrailPassword = formData.getString("testrailPassword");

            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setTestrailHost)
            save();
            return super.configure(req,formData);
        }

        public void setTestrailHost(String host) { this.testrailHost = host; }
        public String getTestrailHost() { return testrailHost; }
        public void setTestrailUser(String user) { this.testrailUser = user; }
        public String getTestrailUser() { return testrailUser; }
        public void setTestrailPassword(String password) { this.testrailPassword = password; }
        public String getTestrailPassword() { return testrailPassword; }
        public void setTestrailInstance(TestRailClient trc) { testrail = trc; }
        public TestRailClient getTestrailInstance() { return testrail; }
    }
}
