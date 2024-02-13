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

import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpException;
import org.jenkinsci.plugins.testrail.JUnit.TestCase;
import org.jenkinsci.plugins.testrail.TestRail.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.jenkinsci.plugins.testrail.Utils.log;
/**
 * Created by Drew on 3/19/14.
 */
public class TestRailClient {
    private String host;
    private String user;
    private String password;

    public void setHost(String host) { this.host = host; }
    public void setUser(String user) { this.user = user; }
    public void setPassword(String password) {this.password = password; }
    public String getHost() { return this.host; }
    public String getUser() { return this.user; }
    public String getPassword() { return this.password; }

    public TestRailClient(String host, String user, String password) {
        this.host = host;
        this.user = user;
        this.password = password;
    }

    private TestRailResponse httpGet(String path)
            throws IOException, URISyntaxException, InterruptedException {
        TestRailResponse response;

        do {
            response = httpGetInt(path);
            if (response.getStatus() == 429) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    log(e.toString());
                }
            }
       } while (response.getStatus() == 429);

       return response;
    }

    private TestRailResponse httpGetInt(String path)
            throws IOException, URISyntaxException, InterruptedException {
        TestRailResponse result;
        //final HttpGet get = new HttpGet(host + "/" + path);
        final HttpRequest req = HttpRequest.newBuilder()
                .GET().uri(new URI(host + "/" + path))
                .build();
        final HttpClient httpclient = HttpClient.newHttpClient();

        final HttpResponse<String> response = httpclient.send(req, HttpResponse.BodyHandlers.ofString());
        return new TestRailResponse(response.statusCode(), response.body());
    }

    private TestRailResponse httpPost(String path, String payload)
        throws IOException, TestRailException {
        TestRailResponse response;

        try {
            do {
                response = httpPostInt(path, payload);
                if (response.getStatus() == 429) {
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException e) {
                        log(e.toString());
                    }
                }
            } while (response.getStatus() == 429);  
        } catch (HttpException | URISyntaxException | InterruptedException e) {
            throw new TestRailException("Posting to " + path + " returned an error!", e);
        }

        if (response.getStatus() != 200) {
            // any status code other than 200 is an error
            throw new TestRailException("Posting to " + path + " returned an error! Response from TestRail is: \n" + response.getBody());
        }
        return response;
    }

    private TestRailResponse httpPostInt(String path, String payload)
            throws HttpException, URISyntaxException, IOException, InterruptedException {
        TestRailResponse result;
        //PostMethod post = new PostMethod(host + "/" + path);
        final HttpRequest post = HttpRequest.newBuilder()
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .uri(new URI(host + "/" + path))
                .header("Accept", "application/json")
                .header("Content-type", "application/json;charset=UTF-8")
                .build();

        HttpClient httpclient = HttpClient.newHttpClient();

        final HttpResponse<String> response = httpclient.send(post, HttpResponse.BodyHandlers.ofString());
        return new TestRailResponse(response.statusCode(), response.body());
    }

    public boolean serverReachable() {
        boolean result = false;
        final HttpClient httpclient = HttpClient.newHttpClient();
        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(new URI(host)).GET().build();
        } catch (URISyntaxException e) {
            log("Bad URI given for host: " + host);
            return false;
        }

        try {
            httpclient.send(request, HttpResponse.BodyHandlers.discarding());
            result = true;
        } catch (InterruptedException | IOException e) {
            // nop - we default to result == false
        }

        return result;
    }

    public boolean authenticationWorks() throws IOException {
        TestRailResponse response;
        try {
            response = httpGet("/index.php?/api/v2/get_projects");
        } catch (URISyntaxException | InterruptedException e) {
            return false;
        }
        return (200 == response.getStatus());
    }

    public Project[] getProjects() throws IOException, ElementNotFoundException {
        String body = "";
        try {
            body = httpGet("/index.php?/api/v2/get_projects").getBody();
        } catch (URISyntaxException | InterruptedException e) {
            log("Failed to retrieve projects! The following error was returned: \n" + e);
        }
        final JSONArray json = new JSONArray(body);
        final Project[] projects = new Project[json.length()];
        for (int i = 0; i < json.length(); i++) {
            final JSONObject o = json.getJSONObject(i);
            final Project p = new Project();
            p.setName(o.getString("name"));
            p.setId(o.getInt("id"));
            projects[i] = p;
        }
        return projects;
    }

    public int getProjectId(String projectName) throws IOException, ElementNotFoundException {
        final Project[] projects = getProjects();
        for (Project project : projects) {
            if (project.getName().equals(projectName)) {
                return project.getId();
            }
        }

        throw new ElementNotFoundException(projectName);
    }

    public Suite[] getSuites(int projectId) throws IOException, ElementNotFoundException {
        String body = "";
        try {
            body = httpGet("/index.php?/api/v2/get_suites/" + projectId).getBody();
        } catch (URISyntaxException | InterruptedException e) {
            log("Failed to retrieve test suites! The following error was returned: \n" + e);
        }

        JSONArray json;
        try {
            json = new JSONArray(body);
        } catch (JSONException e) {
            return new Suite[0];
        }

        Suite[] suites = new Suite[json.length()];
        for (int i = 0; i < json.length(); i++) {
            JSONObject o = json.getJSONObject(i);
            Suite s = new Suite();
            s.setName(o.getString("name"));
            s.setId(o.getInt("id"));
            suites[i] = s;
        }

        return suites;
    }

    public String getCasesString(int projectId, int suiteId) {
        return "index.php?/api/v2/get_cases/" + projectId + "&suite_id=" + suiteId;
    }

    public Case[] getCases(int projectId, int suiteId) throws IOException, ElementNotFoundException {
        // "/#{project_id}&suite_id=#{suite_id}#{section_string}"
        String body = "";
        try {
            body = httpGet("index.php?/api/v2/get_cases/" + projectId + "&suite_id=" + suiteId).getBody();
        } catch (URISyntaxException | InterruptedException e) {
            log("Failed to retrieve test cases! The following error was returned: \n" + e);
        }

        JSONArray json;

        try {
            json = new JSONArray(body);
        } catch (JSONException e) {
            throw new ElementNotFoundException("No cases for project " + projectId + " and suite " + suiteId + "! Response from TestRail is: \n" + body);
        }

        final Case[] cases = new Case[json.length()];
        for (int i = 0; i < json.length(); i++) {
            final JSONObject o = json.getJSONObject(i);
            cases[i] = createCaseFromJson(o);
        }

        return cases;
    }

    public Section[] getSections(int projectId, int suiteId) throws IOException {
        String body = "";
        try {
            body = httpGet("index.php?/api/v2/get_sections/" + projectId + "&suite_id=" + suiteId).getBody();
        } catch (URISyntaxException | InterruptedException e) {
            log("Failed to retrieve sections! The following error was returned: \n" + e);
        }
        final JSONArray json = new JSONArray(body);

        final Section[] sects = new Section[json.length()];
        for (int i = 0; i < json.length(); i++) {
            final JSONObject o = json.getJSONObject(i);
            sects[i] = createSectionFromJSON(o);
        }

        return sects;
    }
    private Section createSectionFromJSON(JSONObject o) {
        Section s = new Section();

        s.setName(o.getString("name"));
        s.setId(o.getInt("id"));

        if (!o.isNull("parent_id")) {
            s.setParentId(String.valueOf(o.getInt("parent_id")));
        } else {
            s.setParentId("null");
        }

        s.setSuiteId(o.getInt("suite_id"));

        return s;
    }

    public Section addSection(String sectionName, int projectId, int suiteId, String parentId) 
            throws IOException, TestRailException {
        //Section section = new Section();
        String payload = new JSONObject().put("name", sectionName).put("suite_id", suiteId).put("parent_id", parentId).toString();
        String body = httpPost("index.php?/api/v2/add_section/" + projectId , payload).getBody();
        JSONObject o = new JSONObject(body);

        return createSectionFromJSON(o);
    }

    private Case createCaseFromJson(JSONObject o) {
        Case s = new Case();
        
        s.setTitle(o.getString("title"));
        s.setId(o.getInt("id"));
        s.setSectionId(o.getInt("section_id"));
        s.setRefs(o.optString("refs"));

        return s;
    }

    public Case addCase(TestCase caseToAdd, int sectionId) 
            throws IOException, TestRailException {
        JSONObject payload = new JSONObject().put("title", caseToAdd.getName());
        if (!StringUtils.isEmpty(caseToAdd.getRefs())) {
            payload.put("refs", caseToAdd.getRefs());
        }

        String body = httpPost("index.php?/api/v2/add_case/" + sectionId, payload.toString()).getBody();
        return createCaseFromJson(new JSONObject(body));
    }

    public TestRailResponse addResultsForCases(int runId, TestRailResults results) 
            throws IOException, TestRailException {
        JSONArray a = new JSONArray();
        for (int i = 0; i < results.getResults().size(); i++) {
            JSONObject o = new JSONObject();
            TestRailResult r = results.getResults().get(i);
            o.put("case_id", r.getCaseId()).put("status_id", r.getStatus().getValue()).put("comment", r.getComment()).put("elapsed", r.getElapsedTimeString());
            a.put(o);
        }

        String payload = new JSONObject().put("results", a).toString();
        log(payload);
        return httpPost("index.php?/api/v2/add_results_for_cases/" + runId, payload);
    }

    public int addRun(int projectId, int suiteId, String milestoneID, String description)
            throws IOException, TestRailException {
        String payload = new JSONObject().put("suite_id", suiteId).put("description", description).put("milestone_id", milestoneID).toString();
        String body = httpPost("index.php?/api/v2/add_run/" + projectId, payload).getBody();
        return new JSONObject(body).getInt("id");
    }

    public Milestone[] getMilestones(int projectId) throws IOException, ElementNotFoundException {
        String body = "";

        try {
            body = httpGet("index.php?/api/v2/get_milestones/" + projectId).getBody();
        } catch (URISyntaxException | InterruptedException e) {
            log("Failed to retrieve milestones! The following error was returned: \n" + e);
        }

        final JSONArray json;

        try {
          json = new JSONArray(body);
        } catch (JSONException e) {
            return new Milestone[0];
        }

        final Milestone[] suites = new Milestone[json.length()];

        for (int i = 0; i < json.length(); i++) {
            final JSONObject o = json.getJSONObject(i);
            final Milestone s = new Milestone();
            s.setName(o.getString("name"));
            s.setId(String.valueOf(o.getInt("id")));
            suites[i] = s;
        }

        return suites;
    }

    public String getMilestoneID(String milestoneName, int projectId) throws IOException, ElementNotFoundException {
      for (final Milestone mstone: getMilestones(projectId)) {
         if (mstone.getName().equals(milestoneName)) {
             return mstone.getId();
         }
      }
      throw new ElementNotFoundException("Milestone id not found.");
    }

    public void closeRun(int runId)
            throws IOException, TestRailException {
        String payload = "";
        httpPost("index.php?/api/v2/close_run/" + runId, payload);
    }
}
