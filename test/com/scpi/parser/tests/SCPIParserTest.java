/*
 * Copyright [2014] [Nicholas Folse]
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.scpi.parser.tests;

import com.scpi.parser.SCPIParser;
import com.scpi.parser.SCPIParser.SCPIMissingHandlerException;
import java.util.logging.Logger;
import junit.framework.Assert;
import org.junit.Test;

/**
 *
 * General test cases for SCPIParser
 */
public class SCPIParserTest {

    private static final Logger LOG = Logger.getLogger(SCPIParserTest.class.getName());
    private final SCPIParser parser = new TestSCPIParser();

    public SCPIParserTest() {
    }

    @Test
    public void testSCPIParser() throws SCPIMissingHandlerException {
        parser.setCacheSizeLimit(20);
        for (String result : parser.accept("*IDN?;VAR:X 23;X?")) {
            System.out.println(result);
        }
        for (String result : parser.accept("VAR:X?")) {
            System.out.println(result);
        }
        for (String result : parser.accept("MEAS:VOLT:DC?;DC?;:MEASure:CURR:AC?")) {
            System.out.println(result);
        }
        for (String result : parser.accept("CONCAT These strings should be \"concatenated.\"")) {
            System.out.println(result);
        }
    }

    /**
     * This test should throw an exception because MEAS:VOLTs:DC? is not
     * registered as a handler.
     *
     * @throws com.scpi.parser.SCPIParser.SCPIMissingHandlerException
     */
    @Test(expected = SCPIMissingHandlerException.class)
    public void errorOnMissingHandler() throws SCPIMissingHandlerException {
        parser.setCacheSizeLimit(20);
        for (String result : parser.accept("MEAS:VOLTs:DC?;:MEASure:CURR:AC?")) {
            System.out.println(result);
        }
    }

    /**
     * Test read-style query performance (with caching). This test should run
     * very quickly with caching enabled. If the cache is disabled (e.g.
     * setCacheSizeLimit(0)), then the test will take much longer.
     */
    @Test
    public void testSCPIParserReadQueryPerformance() {
        parser.setCacheSizeLimit(20);
        for (int i = 0; i < 1000000; i++) {
            try {
                String[] results = parser.accept("MEAS:VOLT:DC?;:MEASure:CURR:AC?");
                double DC = Double.parseDouble(results[0]);
                double AC = Double.parseDouble(results[1]);
                Assert.assertEquals(2.23, DC, 0.001);
                Assert.assertEquals(0.123, AC, 0.001);
            } catch (SCPIParser.SCPIMissingHandlerException e) {
            }
        }
        //System.out.println(parser.getCacheFrequency());
    }

    /**
     * Test queries that contain variable arguments. Non-cached argument queries
     * take much longer to process so this test is limited to 100000 queries.
     * setCacheCommandsWithArguments(true) will result in longer execution time.
     * Cache size should not affect performance of this test greatly.
     *
     * @throws com.scpi.parser.SCPIParser.SCPIMissingHandlerException
     */
    @Test
    public void testSCPIParserWriteQueryPerformance() throws SCPIMissingHandlerException {
        parser.setCacheSizeLimit(20);
        parser.setCacheQueriesWithArguments(false);
        String[] results;
        for (int i = 0; i < 100000; i++) {
            parser.accept("VAR:X " + i);
            results = parser.accept("VAR:X?");
            int readBack = Integer.parseInt(results[0]);
            Assert.assertEquals(i, readBack);

        }
        //System.out.println(parser.getCacheFrequency().size());
        //System.out.println(parser.getCacheFrequency());
    }

    private static class TestSCPIParser extends SCPIParser {

        int varX = 0;
        double voltsDC = 2.23;
        double ampsAC = 0.123;

        TestSCPIParser() {
            addHandler("*IDN?", this::IDN);
            addHandler("VAR:X", this::setX);
            addHandler("VAR:X?", this::getX);
            addHandler("CONCAT", this::concat);
            addHandler("MEASure:VOLTage:DC?", this::measVoltsDC);
            addHandler("MEASure:CURRent:AC?", this::measCurrentAC);
        }

        String concat(String[] args) {
            return String.join(" ", args);
        }

        String IDN(String[] args) {
            return "SCPI Test Parser";
        }

        String getX(String[] args) {
            return Integer.toString(varX);
        }

        String setX(String[] args) {
            if (args.length >= 1) {
                try {
                    int newX = Integer.parseInt(args[0]);
                    varX = newX;
                } catch (Exception e) {
                }
            }
            return null;
        }

        String measVoltsDC(String[] args) {
            return Double.toString(voltsDC);
        }

        String measCurrentAC(String[] args) {
            return Double.toString(ampsAC);
        }
    }

}
