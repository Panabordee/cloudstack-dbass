// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.cloudstack.reverseproxy;

import org.junit.Assert;
import org.junit.Test;

import com.cloud.exception.InvalidParameterValueException;

public class ReverseProxyNameValidationTest {

    private final ReverseProxyManagerImpl manager = new ReverseProxyManagerImpl();

    @Test
    public void testValidNamesAreNormalizedToLowercase() {
        Assert.assertEquals("my-web", manager.validateProxyName("my-web"));
        Assert.assertEquals("my-web", manager.validateProxyName(" My-Web "));
        Assert.assertEquals("web1", manager.validateProxyName("WEB1"));
        Assert.assertEquals("a", manager.validateProxyName("a"));
        Assert.assertEquals("0", manager.validateProxyName("0"));
        Assert.assertEquals("a0", manager.validateProxyName("a0"));
        Assert.assertEquals("a-b", manager.validateProxyName("a-b"));
        Assert.assertEquals("0a", manager.validateProxyName("0a"));
    }

    @Test
    public void testSixtyThreeCharacterLabelIsValid() {
        final String sixtyThreeChars = "a" + "b".repeat(61) + "c";
        Assert.assertEquals(63, sixtyThreeChars.length());
        Assert.assertEquals(sixtyThreeChars, manager.validateProxyName(sixtyThreeChars));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testSixtyFourCharacterLabelIsRejected() {
        manager.validateProxyName("a".repeat(64));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testBlankNameIsRejected() {
        manager.validateProxyName("   ");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testLeadingHyphenIsRejected() {
        manager.validateProxyName("-web");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testTrailingHyphenIsRejected() {
        manager.validateProxyName("web-");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testFqdnIsRejected() {
        manager.validateProxyName("my-web.cloud.company.com");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testSpacesAreRejected() {
        manager.validateProxyName("my web");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUnderscoreIsRejected() {
        manager.validateProxyName("my_web");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testNullNameIsRejected() {
        manager.validateProxyName(null);
    }

    @Test
    public void testValidProtocolsAreNormalizedToLowercase() {
        Assert.assertEquals("http", manager.validateProtocol("http"));
        Assert.assertEquals("http", manager.validateProtocol(" HTTP "));
        Assert.assertEquals("https", manager.validateProtocol("HTTPS"));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testUnsupportedProtocolIsRejected() {
        manager.validateProtocol("ftp");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testBlankProtocolIsRejected() {
        manager.validateProtocol(null);
    }
}
