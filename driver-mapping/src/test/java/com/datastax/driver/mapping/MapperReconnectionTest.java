/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.mapping;

import static com.datastax.driver.core.CreateCCM.TestMode.PER_METHOD;
import static com.datastax.driver.mapping.MapperTest.User;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import org.testng.annotations.Test;

import com.datastax.driver.core.CCMConfig;
import com.datastax.driver.core.CCMTestsSupport;
import com.datastax.driver.core.CreateCCM;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.NoHostAvailableException;

@CreateCCM(PER_METHOD)
@CCMConfig(dirtiesContext = true, numberOfNodes = 1)
public class MapperReconnectionTest extends CCMTestsSupport {
    @Override
    public void onTestContextInitialized() {
        // We'll allow to generate those create statement from the annotated entities later, but it's currently
        // a TODO
        execute("CREATE TABLE users (user_id uuid PRIMARY KEY, name text, email text, year int, gender text)");
    }

    @Test(groups = "short")
    public void reproduce_no_available_host_bug() throws Exception{
        Session session = session();

        MappingManager manager = new MappingManager(session);

        Mapper<User> m = manager.mapper(User.class);

        User u1 = new User("Paul", "paul@gmail.com");

        m.save(u1);

        try {
            //shut down the only node in cluster, to simulate all connections to nodes are disconnected
            ccm().stop(1);
            ccm().waitForDown(1);
            m.get(u1.getUserId());
            fail("NoHostAvailableException not thrown");
        }catch(NoHostAvailableException e){
            e.getStackTrace();
        }

        try {
            //up the session again, and make on more query
            ccm().start(1);
            ccm().waitForUp(1);
            //sleep for 1000, in case reconnection is not done yet
            Thread.sleep(1000);
            m.get(u1);
            fail("NoHostAvailableException not thrown");
        }catch(NoHostAvailableException e){
            e.getStackTrace();
        }
    }

    @Test(groups = "short")
    public void test_clear_cache() throws Exception{
        Session session = session();

        MappingManager manager = new MappingManager(session);

        Mapper<User> m = manager.mapper(User.class);

        User u1 = new User("Paul", "paul@gmail.com");

        m.save(u1);

        try {
            //shut down the only node in cluster, to simulate all connections to nodes are disconnected
            ccm().stop(1);
            ccm().waitForDown(1);
            m.get(u1.getUserId());
            fail("NoHostAvailableException not thrown");
        }catch(NoHostAvailableException e){
            e.getStackTrace();
        }

        ccm().start(1);
        ccm().waitForUp(1);
        //sleep for 1000, in case reconnection is not done yet
        Thread.sleep(1000);

        //clear the prepared statement cache in mapper
        m.clearCache();
        User result = m.get(u1.getUserId());
        assertTrue(result.equals(u1));

    }


}
