/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * Copyright (c) 2015 Christopher Simons
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.TextPage;
import com.gargoylesoftware.htmlunit.WebAssert;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlFormUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.FilePath;
import hudson.Functions;
import hudson.model.queue.QueueTaskFuture;
import hudson.slaves.RetentionStrategy;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BatchFile;
import hudson.tasks.Shell;
import hudson.util.TextFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jenkins.model.Jenkins;
import jenkins.model.ProjectNamingStrategy;
import jenkins.security.apitoken.ApiTokenTestHelper;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.FailureBuilder;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.JenkinsRule.WebClient;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.MockFolder;
import org.jvnet.hudson.test.RunLoadCounter;
import org.jvnet.hudson.test.SleepBuilder;
import org.jvnet.hudson.test.recipes.LocalData;

/**
 * @author Kohsuke Kawaguchi
 */
public class JobTest {

    @Rule public JenkinsRule j = new JenkinsRule();

    @SuppressWarnings("unchecked")
    @Test public void jobPropertySummaryIsShownInMainPage() throws Exception {
        AbstractProject project = j.createFreeStyleProject();
        project.addProperty(new JobPropertyImpl("NeedleInPage"));
                
        HtmlPage page = j.createWebClient().getPage(project);
        WebAssert.assertTextPresent(page, "NeedleInPage");
    }

    @Test public void buildNumberSynchronization() throws Exception {
        AbstractProject project = j.createFreeStyleProject();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch stopLatch = new CountDownLatch(2);
        BuildNumberSyncTester test1 = new BuildNumberSyncTester(project, startLatch, stopLatch, true);
        BuildNumberSyncTester test2 = new BuildNumberSyncTester(project, startLatch, stopLatch, false);
        new Thread(test1).start();
        new Thread(test2).start();

        startLatch.countDown();
        stopLatch.await();

        assertTrue(test1.message, test2.passed);
        assertTrue(test2.message, test2.passed);
    }

    public static class BuildNumberSyncTester implements Runnable {
        private final AbstractProject p;
        private final CountDownLatch start;
        private final CountDownLatch stop;
        private final boolean assign;

        String message;
        boolean passed;

        BuildNumberSyncTester(AbstractProject p, CountDownLatch l1, CountDownLatch l2, boolean b) {
            this.p = p;
            this.start = l1;
            this.stop = l2;
            this.assign = b;
            this.message = null;
            this.passed = false;
        }

        @Override
        public void run() {
            try {
                start.await();

                for (int i = 0; i < 100; i++) {
                    int buildNumber = -1, savedBuildNumber = -1;
                    TextFile f;

                    synchronized (p) {
                        if (assign) {
                            buildNumber = p.assignBuildNumber();
                            f = p.getNextBuildNumberFile();
                            if (f == null) {
                                this.message = "Could not get build number file";
                                this.passed = false;
                                return;
                            }
                            savedBuildNumber = Integer.parseInt(f.readTrim());
                            if (buildNumber != savedBuildNumber - 1) {
                                this.message = "Build numbers don't match (" + buildNumber + ", " + (savedBuildNumber-1) + ")";
                                this.passed = false;
                                return;
                            }
                        } else {
                            buildNumber = p.getNextBuildNumber() + 100;
                            p.updateNextBuildNumber(buildNumber);
                            f = p.getNextBuildNumberFile();
                            if (f == null) {
                                this.message = "Could not get build number file";
                                this.passed = false;
                                return;
                            }
                            savedBuildNumber = Integer.parseInt(f.readTrim());
                            if (buildNumber != savedBuildNumber) {
                                this.message = "Build numbers don't match (" + buildNumber + ", " + savedBuildNumber + ")";
                                this.passed = false;
                                return;
                            }
                        }
                    }
                }

                this.passed = true;
            }
            catch (InterruptedException e) {}
            catch (IOException e) {
                throw new AssertionError("Failed to assign build number", e);
            }
            finally {
                stop.countDown();
            }
        }
    }

    public static class JobPropertyImpl extends JobProperty<Job<?,?>> {
        public static DescriptorImpl DESCRIPTOR = new DescriptorImpl();
        private final String testString;
        
        public JobPropertyImpl(String testString) {
            this.testString = testString;
        }
        
        public String getTestString() {
            return testString;
        }

        @Override
        public JobPropertyDescriptor getDescriptor() {
            return DESCRIPTOR;
        }

        private static final class DescriptorImpl extends JobPropertyDescriptor {
            @Override
            public String getDisplayName() {
                return "";
            }
        }
    }

    @Test public void readPermission() throws Exception {
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        FreeStyleProject p = j.createFreeStyleProject("testJob");
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy().
            grant(Jenkins.READ).everywhere().toEveryone().
            grant(Item.READ).onItems(p).to("joe"));
        JenkinsRule.WebClient wc = j.createWebClient();
        wc.assertFails("job/testJob/", HttpURLConnection.HTTP_NOT_FOUND);
        wc.assertFails("jobCaseInsensitive/testJob/", HttpURLConnection.HTTP_NOT_FOUND);
        wc.withBasicCredentials("joe");
        // Verify we can access both URLs:
        wc.goTo("job/testJob/");
        wc.goTo("jobCaseInsensitive/TESTJOB/");
    }

    @LocalData
    @Test public void configDotXmlPermission() throws Exception {
        ApiTokenTestHelper.enableLegacyBehavior();
        
        j.jenkins.setCrumbIssuer(null);
        JenkinsRule.WebClient wc = j.createWebClient();
        boolean saveEnabled = Item.EXTENDED_READ.getEnabled();
        Item.EXTENDED_READ.setEnabled(true);
        try {
            wc.assertFails("job/testJob/config.xml", HttpURLConnection.HTTP_FORBIDDEN);

            wc.setThrowExceptionOnFailingStatusCode(false);
            
            // Has CONFIGURE and EXTENDED_READ permission
            wc.withBasicApiToken(User.getById("alice", true));  
            tryConfigDotXml(wc, HttpURLConnection.HTTP_INTERNAL_ERROR, "Both perms; should get 500");

            // Has only CONFIGURE permission (this should imply EXTENDED_READ)
            wc.withBasicApiToken(User.getById("bob", true));  
            tryConfigDotXml(wc, HttpURLConnection.HTTP_INTERNAL_ERROR, "Config perm should imply EXTENDED_READ");

            // Has only EXTENDED_READ permission
            wc.withBasicApiToken(User.getById("charlie", true));  
            tryConfigDotXml(wc, HttpURLConnection.HTTP_FORBIDDEN, "No permission, should get 403");
        } finally {
            Item.EXTENDED_READ.setEnabled(saveEnabled);
        }
    }

    private static void tryConfigDotXml(JenkinsRule.WebClient wc, int status, String msg) throws Exception {
        // Verify we can GET the config.xml:
        Page p = wc.goTo("job/testJob/config.xml", "application/xml");
        assertEquals("Retrieving config.xml should be ok", HttpURLConnection.HTTP_OK, p.getWebResponse().getStatusCode());
        
        // This page is a simple form to POST to /job/testJob/config.xml
        // But it posts invalid data so we expect 500 if we have permission, 403 if not
        HtmlPage page = wc.goTo("userContent/post.html");
        p = HtmlFormUtil.submit(page.getForms().get(0));
        assertEquals(msg, status, p.getWebResponse().getStatusCode());
        
        p = wc.goTo("logout");
        assertEquals("To logout should be ok", HttpURLConnection.HTTP_OK, p.getWebResponse().getStatusCode());
    }

    @LocalData @Issue("JENKINS-6371")
    @Test public void getArtifactsUpTo() {
        // There was a bug where intermediate directories were counted,
        // so too few artifacts were returned.
        Run r = j.jenkins.getItemByFullName("testJob", Job.class).getLastCompletedBuild();
        assertEquals(3, r.getArtifacts().size());
        assertEquals(3, r.getArtifactsUpTo(3).size());
        assertEquals(2, r.getArtifactsUpTo(2).size());
        assertEquals(1, r.getArtifactsUpTo(1).size());
    }

    @Issue("JENKINS-10182")
    @Test public void emptyDescriptionReturnsEmptyPage() throws Exception {
        // A NPE was thrown if a job had a null (empty) description.
        JenkinsRule.WebClient wc = j.createWebClient();
        FreeStyleProject project = j.createFreeStyleProject("project");
        project.setDescription("description");
        assertEquals("description", ((TextPage) wc.goTo("job/project/description", "text/plain")).getContent());
        project.setDescription(null);
        assertEquals("", ((TextPage) wc.goTo("job/project/description", "text/plain")).getContent());
    }
    
    @Test public void projectNamingStrategy() throws Exception {
        j.jenkins.setProjectNamingStrategy(new ProjectNamingStrategy.PatternProjectNamingStrategy("DUMMY.*", false));
        try {
            final FreeStyleProject p = j.createFreeStyleProject("DUMMY_project");
            assertNotNull("no project created", p);
            assertThrows(Failure.class, () -> j.createFreeStyleProject("project"));
        } finally {
            // set it back to the default naming strategy, otherwise all other tests would fail to create jobs!
            j.jenkins.setProjectNamingStrategy(ProjectNamingStrategy.DEFAULT_NAMING_STRATEGY);
        }
        j.createFreeStyleProject("project");
    }

    @Issue("JENKINS-16023")
    @Test public void getLastFailedBuild() throws Exception {
        final FreeStyleProject p = j.createFreeStyleProject();
        RunLoadCounter.prepare(p);
        p.getBuildersList().add(new FailureBuilder());
        j.buildAndAssertStatus(Result.FAILURE, p);
        j.buildAndAssertStatus(Result.FAILURE, p);
        j.buildAndAssertStatus(Result.FAILURE, p);
        p.getBuildersList().remove(FailureBuilder.class);
        j.buildAndAssertSuccess(p);
        j.buildAndAssertSuccess(p);
        j.buildAndAssertSuccess(p);
        assertEquals(6, p.getLastSuccessfulBuild().getNumber());
        assertEquals(3, RunLoadCounter.assertMaxLoads(p, 1, new Callable<Integer>() {
            @Override public Integer call() {
                return p.getLastFailedBuild().getNumber();
            }
        }).intValue());
    }

    @Issue("JENKINS-19764")
    @Test public void testRenameWithCustomBuildsDirWithSubdir() throws Exception {
        j.jenkins.setRawBuildsDir("${JENKINS_HOME}/builds/${ITEM_FULL_NAME}/builds");
        final FreeStyleProject p = j.createFreeStyleProject();
        j.buildAndAssertSuccess(p);
        p.renameTo("different-name");
    }

    @Issue("JENKINS-44657")
    @Test public void testRenameWithCustomBuildsDirWithBuildsIntact() throws Exception {
        j.jenkins.setRawBuildsDir("${JENKINS_HOME}/builds/${ITEM_FULL_NAME}/builds");
        final FreeStyleProject p = j.createFreeStyleProject();
        final File oldBuildsDir = p.getBuildDir();
        j.buildAndAssertSuccess(p);
        String oldDirContent = dirContent(oldBuildsDir);
        p.renameTo("different-name");
        final File newBuildDir = p.getBuildDir();
        assertNotNull(newBuildDir);
        assertNotEquals(oldBuildsDir.getAbsolutePath(), newBuildDir.getAbsolutePath());
        String newDirContent = dirContent(newBuildDir);
        assertEquals(oldDirContent, newDirContent);
    }

    @Issue("JENKINS-44657")
    @Test public void testRenameWithCustomBuildsDirWithBuildsIntactInFolder() throws Exception {
        j.jenkins.setRawBuildsDir("${JENKINS_HOME}/builds/${ITEM_FULL_NAME}/builds");
        final MockFolder f = j.createFolder("F");

        final FreeStyleProject p1 = f.createProject(FreeStyleProject.class, "P1");
        j.buildAndAssertSuccess(p1);
        File oldP1BuildsDir = p1.getBuildDir();
        final String oldP1DirContent = dirContent(oldP1BuildsDir);
        f.renameTo("different-name");

        File newP1BuildDir = p1.getBuildDir();
        assertNotNull(newP1BuildDir);
        assertNotEquals(oldP1BuildsDir.getAbsolutePath(), newP1BuildDir.getAbsolutePath());
        String newP1DirContent = dirContent(newP1BuildDir);
        assertEquals(oldP1DirContent, newP1DirContent);

        final FreeStyleProject p2 = f.createProject(FreeStyleProject.class, "P2");
        if (Functions.isWindows()) {
            p2.getBuildersList().add(new BatchFile("echo hello > hello.txt"));
        } else {
            p2.getBuildersList().add(new Shell("echo hello > hello.txt"));
        }
        p2.getPublishersList().add(new ArtifactArchiver("*.txt"));
        j.buildAndAssertSuccess(p2);

        File oldP2BuildsDir = p2.getBuildDir();
        final String oldP2DirContent = dirContent(oldP2BuildsDir);
        FreeStyleBuild b2 = p2.getBuilds().getLastBuild();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b2.getLogText().writeRawLogTo(0, out);
        final String oldB2Log = out.toString();
        assertTrue(b2.getArtifactManager().root().child("hello.txt").exists());
        f.renameTo("something-else");

        //P1 check again
        newP1BuildDir = p1.getBuildDir();
        assertNotNull(newP1BuildDir);
        assertNotEquals(oldP1BuildsDir.getAbsolutePath(), newP1BuildDir.getAbsolutePath());
        newP1DirContent = dirContent(newP1BuildDir);
        assertEquals(oldP1DirContent, newP1DirContent);

        //P2 check

        b2 = p2.getBuilds().getLastBuild();
        assertNotNull(b2);
        out = new ByteArrayOutputStream();
        b2.getLogText().writeRawLogTo(0, out);
        final String newB2Log = out.toString();
        assertEquals(oldB2Log, newB2Log);
        assertTrue(b2.getArtifactManager().root().child("hello.txt").exists());

        File newP2BuildDir = p2.getBuildDir();
        assertNotNull(newP2BuildDir);
        assertNotEquals(oldP2BuildsDir.getAbsolutePath(), newP2BuildDir.getAbsolutePath());
        String newP2DirContent = dirContent(newP2BuildDir);
        assertEquals(oldP2DirContent, newP2DirContent);
    }

    private String dirContent(File dir) throws IOException, InterruptedException {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        StringBuilder str = new StringBuilder();
        final FilePath[] list = new FilePath(dir).list("**/*");
        Arrays.sort(list, Comparator.comparing(FilePath::getRemote));
        for (FilePath path : list) {
            str.append(relativePath(dir, path));
            str.append(' ').append(path.length()).append('\n');
        }
        return str.toString();
    }

    private String relativePath(File base, FilePath path) throws IOException, InterruptedException {
        if (path.absolutize().getRemote().equals(base.getAbsolutePath())) {
            return "";
        } else {
            final String s = relativePath(base, path.getParent());
            if (s.isEmpty()) {
                return path.getName();
            } else {
                return s + "/" + path.getName();
            }
        }
    }

    @Issue("JENKINS-30502")
    @Test
    public void testRenameTrimsLeadingSpace() throws Exception {
        tryRename("myJob1", " foo", "foo");
    }

    @Issue("JENKINS-30502")
    @Test
    public void testRenameTrimsTrailingSpace() throws Exception {
        tryRename("myJob2", "foo ", "foo");
    }

    @Issue("JENKINS-30502")
    @Test
    public void testAllowTrimmingByUser() throws Exception {
        assumeFalse("Unix-only test.", Functions.isWindows());
        tryRename("myJob3 ", "myJob3", "myJob3");
    }

    @Issue("JENKINS-30502")
    @Test
    public void testRenameWithLeadingSpaceTrimsLeadingSpace() throws Exception {
        assumeFalse("Unix-only test.", Functions.isWindows());
        tryRename(" myJob4", " foo", "foo");
    }

    @Issue("JENKINS-30502")
    @Test
    public void testRenameWithLeadingSpaceTrimsTrailingSpace()
            throws Exception {
        assumeFalse("Unix-only test.", Functions.isWindows());
        tryRename(" myJob5", "foo ", "foo");
    }

    @Issue("JENKINS-30502")
    @Test
    public void testRenameWithTrailingSpaceTrimsTrailingSpace()
            throws Exception {
        assumeFalse("Unix-only test.", Functions.isWindows());
        tryRename("myJob6 ", "foo ", "foo");
    }

    @Issue("JENKINS-30502")
    @Test
    public void testRenameWithTrailingSpaceTrimsLeadingSpace()
            throws Exception {
        assumeFalse("Unix-only test.", Functions.isWindows());
        tryRename("myJob7 ", " foo", "foo");
    }

    @Issue("JENKINS-63899")
    @Test
    public void testRenameNonLatin() throws Exception {
        tryRename("myJob8", "блины", "блины");
    }

    @Test
    public void testRenameSpaceInBetween() throws Exception {
        tryRename("myJob9", "my Job9", "my Job9");
    }

    @Issue("JENKINS-35160")
    @Test
    public void interruptOnDelete() throws Exception {
        assumeFalse("TODO: Windows container agents do not have enough resources to run this test", Functions.isWindows() && System.getenv("CI") != null);
        j.jenkins.setNumExecutors(2);
        Queue.getInstance().maintain();
        final FreeStyleProject p = j.createFreeStyleProject();
        p.addProperty(new ParametersDefinitionProperty(
                new StringParameterDefinition("dummy", "0")));
        p.setConcurrentBuild(true);
        p.getBuildersList().add(new SleepBuilder(Long.MAX_VALUE));  // we want the uninterrupted job to run for long time
        FreeStyleBuild build1 = p.scheduleBuild2(0).getStartCondition().get();
        FreeStyleBuild build2 = p.scheduleBuild2(0).getStartCondition().get();
        QueueTaskFuture<FreeStyleBuild> build3 = p.scheduleBuild2(0);
        long start = System.nanoTime();
        p.delete();
        long end = System.nanoTime();
        assertThat(end - start, Matchers.lessThan(TimeUnit.SECONDS.toNanos(1)));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(build1));
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(build2));
        assertTrue(build3.isCancelled());
    }

    @Issue("SECURITY-1868")
    @Test public void noXssPossible() throws Exception {
        assumeFalse("TODO: Windows container agents do not have enough resources to run this test", Functions.isWindows() && System.getenv("CI") != null);
        String desiredNodeName = "agent is a better name2 <script>alert(123)</script>";
        String initialNodeName = "agent is a better name";

        NameChangingNode node = new NameChangingNode(j, initialNodeName);
        j.jenkins.addNode(node);

        j.waitOnline(node);

        j.jenkins.setNumExecutors(0);

        FreeStyleProject p = j.createFreeStyleProject();
        j.buildAndAssertSuccess(p);

        node.setVirtualName(desiredNodeName);

        JenkinsRule.WebClient wc = j.createWebClient();
        AtomicReference<String> alertContent = new AtomicReference<>("");

        wc.setAlertHandler((page, s1) ->
                alertContent.set(s1)
        );

        wc.withThrowExceptionOnFailingStatusCode(false);
        wc.getPage(p, "buildTimeTrend");

        assertEquals("", alertContent.get());
    }

    /**
     * This special class was created just to avoid running the test on unix only
     * as the only limitation is the file path, if we change only the name, the XSS is possible also under windows
     */
    static class NameChangingNode extends Slave {
        private String virtualName;

        NameChangingNode(JenkinsRule j, String name) throws Exception {
            super(name, "dummy", j.createTmpDir().getPath(), "1", Node.Mode.NORMAL, "", j.createComputerLauncher(null), RetentionStrategy.NOOP, Collections.emptyList());
        }

        public void setVirtualName(String virtualName) {
            this.virtualName = virtualName;
        }

        @NonNull
        @Override
        public String getNodeName() {
            if (virtualName != null) {
                return virtualName;
            } else {
                return super.getNodeName();
            }
        }
    }

    private void tryRename(String initialName, String submittedName,
            String correctResult) throws Exception {
        j.jenkins.setCrumbIssuer(null);

        FreeStyleProject job = j.createFreeStyleProject(initialName);
        WebClient wc = j.createWebClient();
        HtmlForm form = wc.getPage(job, "confirm-rename").getFormByName("config");
        form.getInputByName("newName").setValueAttribute(submittedName);
        HtmlPage resultPage = j.submit(form);

        String urlString = MessageFormat.format(
                "/job/{0}/", Functions.encode(correctResult));

        assertThat(resultPage.getUrl().toString(), endsWith(urlString));
    }
}
