/*
 * Copyright (C) 2016 The Holodeck B2B Team, Sander Fieten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.holodeckb2b.ebms3.handlers.inflow;

import org.apache.axiom.om.OMElement;
import org.apache.axiom.soap.SOAPEnvelope;
import org.apache.axiom.soap.SOAPHeaderBlock;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.engine.Handler;
import org.holodeckb2b.ebms3.constants.MessageContextProperties;
import org.holodeckb2b.ebms3.mmd.xml.MessageMetaData;
import org.holodeckb2b.ebms3.packaging.Messaging;
import org.holodeckb2b.ebms3.packaging.SOAPEnv;
import org.holodeckb2b.ebms3.packaging.UserMessage;
import org.holodeckb2b.interfaces.core.HolodeckB2BCoreInterface;
import org.holodeckb2b.testhelpers.HolodeckCore;
import org.holodeckb2b.testhelpers.MP;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.util.Iterator;

import static org.junit.Assert.*;

/**
 * Created at 23:28 21.09.16
 *
 * @author Timur Shakuov (t.shakuov at gmail.com)
 */
public class ReadUserMessageTest {

    private static String baseDir;

    private ReadUserMessage handler;

    @BeforeClass
    public static void setUpClass() {
        baseDir = ReadUserMessageTest.class.getClassLoader()
                .getResource("multihop").getPath();
        HolodeckB2BCoreInterface.setImplementation(new HolodeckCore(baseDir));
    }

    @Before
    public void setUp() throws Exception {
        handler = new ReadUserMessage();
    }

    @After
    public void tearDown() throws Exception {

    }

    /**
     * Test construction of the user message to be successfully consumed by
     * {@link org.holodeckb2b.ebms3.handlers.inflow.ReadUserMessage ReadUserMessage} handler
     */
    @Test
    public void testProcessing() {
        final String mmdPath =
                this.getClass().getClassLoader()
                        .getResource("multihop/icloud/full_mmd.xml").getPath();
        final File f = new File(mmdPath);
        MessageMetaData mmd = null;
        try {
            mmd = MessageMetaData.createFromFile(f);
        } catch (final Exception e) {
            fail("Unable to test because MMD could not be read correctly!");
        }

        // Creating SOAP envelope
        SOAPEnvelope env =
                SOAPEnv.createEnvelope(SOAPEnv.SOAPVersion.SOAP_12);
        assertEquals(MP.ENVELOPE, env.toString());
        // Adding header
        SOAPHeaderBlock headerBlock = Messaging.createElement(env);

        assertEquals(MP.ENVELOPE_WITH_HEADER_AND_MESSAGING, env.toString());
        // Adding UserMessage from mmd
        OMElement userMessage = UserMessage.createElement(headerBlock, mmd);
        assertEquals(MP.USER_MESSAGE_WITH_XMLNS, userMessage.toString());

        assertEquals(MP.ENVELOPE_WITH_HEADER_MESSAGING_AND_USER_MESSAGE, env.toString());

        MessageContext mc = new MessageContext();

        // Setting input message property
        mc.setProperty(MessageContextProperties.IN_USER_MESSAGE, userMessage);
        try {
            mc.setEnvelope(env);
        } catch (AxisFault axisFault) {
            fail(axisFault.getMessage());
        }

        SOAPHeaderBlock messaging = Messaging.getElement(mc.getEnvelope());
        System.out.println("messaging: " + messaging);
        assertNotNull(messaging);

        final Iterator<?> it = UserMessage.getElements(messaging);

        assertNotNull(it);
        assertTrue(it.hasNext());

        final OMElement umElement = (OMElement) it.next();

        assertEquals(MP.USER_MESSAGE_WITH_XMLNS, umElement.toString());

        try {
            assertNotNull(messaging);
            Handler.InvocationResponse invokeResp = handler.invoke(mc);
            assertNotNull(invokeResp);
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }
}