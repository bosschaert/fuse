/**
 * Copyright (C) FuseSource, Inc.
 * http://fusesource.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fusesource.fabric.itests.paxexam;

import org.fusesource.fabric.api.Container;
import org.fusesource.fabric.api.FabricService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.linkedin.zookeeper.client.IZKClient;
import org.openengsb.labs.paxexam.karaf.options.LogLevelOption;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.Configuration;
import org.ops4j.pax.exam.junit.ExamReactorStrategy;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.ops4j.pax.exam.spi.reactors.AllConfinedStagedReactorFactory;


import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.keepRuntimeFolder;
import static org.openengsb.labs.paxexam.karaf.options.KarafDistributionOption.logLevel;

@RunWith(JUnit4TestRunner.class)
@ExamReactorStrategy(AllConfinedStagedReactorFactory.class)
public class CreateSshAgentTest extends FabricCommandsTestSupport {

    private String host;
    private String port;
    private String username;
    private String password;


    /**
     * Returns true if all the requirements for running this test are meet.
     * @return
     */
    public boolean isReady() {
        return
                host != null && port != null && username != null & password != null &&
                        !host.isEmpty() && !port.isEmpty() && !username.isEmpty() && !password.isEmpty();
    }

    @Before
    public void setUp() {
        host = System.getProperty("fabricitest.ssh.host");
        port = System.getProperty("fabricitest.ssh.port");
        username = System.getProperty("fabricitest.ssh.username");
        password = System.getProperty("fabricitest.ssh.password");
    }

    @After
    public void tearDown() {
        if (isReady()) {
            executeCommand("fabric:container-connect ssh1 osgi:stop --force 0");
        }
    }

    @Test
    public void testSshContainerProvider() throws Exception {
        FabricService fabricService = getOsgiService(FabricService.class);
        assertNotNull(fabricService);
        System.err.println(executeCommand("fabric:create"));

        //Wait for zookeeper service to become available.
        IZKClient zooKeeper = getOsgiService(IZKClient.class);

        fabricService.createContainer("ssh://"+username+":"+password+"@"+host+":"+port,"ssh1");
        Thread.sleep(5*DEFAULT_TIMEOUT);
        System.err.println(executeCommand("fabric:container-list"));
        Container container = fabricService.getContainer("ssh1");
        assertTrue(container.isAlive());
    }

    @Configuration
    public Option[] config() {
        return new Option[]{
                fabricDistributionConfiguration(), keepRuntimeFolder(),
                copySystemProperty("fabricitest.ssh.username"),
                copySystemProperty("fabricitest.ssh.password"),
                copySystemProperty("fabricitest.ssh.host"),
                copySystemProperty("fabricitest.ssh.port"),

                logLevel(LogLevelOption.LogLevel.ERROR)};
    }
}