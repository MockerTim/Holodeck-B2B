/*
 * Copyright (C) 2014 The Holodeck B2B Team, Sander Fieten
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

package org.holodeckb2b.ebms3.handlers.outflow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.axis2.AxisFault;
import org.apache.axis2.context.MessageContext;
import org.holodeckb2b.common.config.Config;
import org.holodeckb2b.common.exceptions.DatabaseException;
import org.holodeckb2b.common.handler.BaseHandler;
import org.holodeckb2b.common.messagemodel.IErrorMessage;
import org.holodeckb2b.common.messagemodel.IPullRequest;
import org.holodeckb2b.common.messagemodel.IReceipt;
import org.holodeckb2b.common.messagemodel.IUserMessage;
import org.holodeckb2b.ebms3.constants.MessageContextProperties;
import org.holodeckb2b.ebms3.constants.ProcessingStates;
import org.holodeckb2b.ebms3.persistent.dao.MessageUnitDAO;
import org.holodeckb2b.ebms3.persistent.message.ErrorMessage;
import org.holodeckb2b.ebms3.persistent.message.PullRequest;
import org.holodeckb2b.ebms3.persistent.message.Receipt;
import org.holodeckb2b.ebms3.persistent.message.UserMessage;
import org.holodeckb2b.ebms3.util.MessageContextUtils;

/**
 * Is the first handler of the out flow and is responsible for preparing a response by checking if the handlers in the
 * in flow prepared message units that should be sent as response. If they did the message units are copied to the out
 * flow message context to make them available to the other handlers in the out flow.
 * 
 * @author Sander Fieten <sander at holodeck-b2b.org>
 */
public class PrepareResponseMessage extends BaseHandler {

    @Override
    protected byte inFlows() {
        return OUT_FLOW | OUT_FAULT_FLOW | RESPONDER;
    }

    @Override
    protected InvocationResponse doProcessing(MessageContext mc) throws AxisFault {
        // Check which response message units were set during in flow, starting
        // with user message
        log.debug("Check for response user message unit");
        UserMessage um = (UserMessage) MessageContextUtils.getPropertyFromInMsgCtx(mc, 
                                                                      MessageContextProperties.OUT_USER_MESSAGE);
        if (um != null) {
            log.debug("Response contains an user message unit");
            // Copy to current context so it gets processed correctly
            mc.setProperty(MessageContextProperties.OUT_USER_MESSAGE, um);
        } else
            log.debug("Response does not contain a user message unit");

        // Check if there is a receipt signal messages to be included
        log.debug("Check for receipt signal to be included");
        Receipt receipt = (Receipt) MessageContextUtils.getPropertyFromInMsgCtx(mc, 
                                                                      MessageContextProperties.RESPONSE_RECEIPT);
        if (receipt != null) {
            log.debug("Response contains a receipt signal");
            // Copy to current context so it gets processed correctly
            MessageContextUtils.addReceiptToSend(mc, receipt);
        } else
            log.debug("Response does not contain receipt signal");
        
        // Check if there are error signal messages to be included
        log.debug("Check for error signals generated during in flow to be included");
        Collection<ErrorMessage> errors = (Collection<ErrorMessage>) MessageContextUtils.getPropertyFromInMsgCtx(mc, 
                                                                      MessageContextProperties.OUT_ERROR_SIGNALS);
        if (errors == null || errors.isEmpty())
            log.debug("Response does not contain error signal(s)");
        else if (errors.size() > 1) {
            // The were multiple error signals generated in the in flow, check if bundling is allowed
            log.debug("Response contains multiple error signals");
            if (Config.allowSignalBundling()) {
                // Bundling is enabled, so include all errors
                log.debug("Bundling allowed, add all errors to response");
                // Copy to current context so it gets processed correctly
                mc.setProperty(MessageContextProperties.OUT_ERROR_SIGNALS, errors);
            } else {
                // Bundling not allowed, select one error signal to include
                log.debug("Bundling is not allowed, select one error to report");
                ErrorMessage include = selectError(errors, mc);
                errors.remove(include);
                // The other errors can not be further processed, so change their state to failed
                setFailed(errors);
                log.debug("Include the selected error [msgID/refTo" + include.getMessageId() + "/" 
                                + include.getRefToMessageId() + "] for processing");                
                MessageContextUtils.addErrorSignalToSend(mc, include);
            }
        } else {
            log.debug("Response does contain one error signal");
            MessageContextUtils.addErrorSignalToSend(mc, errors.iterator().next());
        }
        
        return InvocationResponse.CONTINUE;
    }    
    
    /**
     * Is a helper method to select the error signal that has the highest priority of sending. The priority of error 
     * signals is determined by the message unit they reference and as follows:<ol>
     * <li>no referenced message unit: The error is to the complete message and can not be related to a message unit. 
     * This indicates a general problem with the message and should be reported;</li>
     * <li>UserMessage</li>
     * <li>PullRequest</li>
     * <li>Error or Receipt</li>
     * </ol>
     * 
     * @param errors    The collection of errors that were generated for the received message
     * @param mc        The current [out flow] messsage context
     * @return          The {@link ErrorMessage} to include in the response
     */
    private ErrorMessage selectError(Collection<ErrorMessage> errors, MessageContext mc) {
        ErrorMessage    r = null;        
        int             cp = -1; // The prio of the currently selected err, 0=Error or Receipt, 1=PullReq, 2=UsrMsg
        
        log.debug("Get the messageIds and types of received message units");
        Map<String, Class>  rcvdMsgs = getReceivedMessageUnits(mc);
        // Now select the error with highest prio
        for(ErrorMessage e : errors) {
            String refTo = e.getRefToMessageId();
            if (refTo == null || refTo.isEmpty()) {
                log.debug("Select general error (without refTo)");
                r = e; // This is the error signal that does not reference a message unit
                break; 
            } else {
                log.debug("Check which type of MU is referenced by error");
                Class type = rcvdMsgs.get(refTo);
                if (type.equals(IUserMessage.class)) {
                    log.debug("Select error referencing the UserMessage unit");
                    r = e; cp = 2;                    
                } else if (type.equals(IPullRequest.class) && cp < 1) {
                    log.debug("Select error referencing the PullRequest unit");
                    r = e; cp = 1;
                } else if (cp == -1) { 
                    // Error references either Error or Receipt which have same prio, just select first
                    log.debug("Select error referencing the PullRequest unit");
                    r = e; cp = 0;
                }
            }
        }
        return r;
    }
    
    /**
     * Gets all message ids and the type of message unit of the message units received. 
     * 
     * @param mc    The current [out flow] message context
     * @return      {@link Map<String, Class>} containing all messageIds and their message unit type 
     */
    private Map<String, Class> getReceivedMessageUnits(MessageContext mc) {
        HashMap<String, Class>   reqMUs = new HashMap<String, Class>();
        
        UserMessage userMsg = (UserMessage)
                MessageContextUtils.getPropertyFromInMsgCtx(mc, MessageContextProperties.IN_USER_MESSAGE);
        if (userMsg != null) {
            log.debug("Request message contained an User Message");
            reqMUs.put(userMsg.getMessageId(), IUserMessage.class);
        }
        PullRequest pullReq = (PullRequest) 
                MessageContextUtils.getPropertyFromInMsgCtx(mc, MessageContextProperties.IN_PULL_REQUEST);
        if (pullReq != null) {
            log.debug("Request message contained a PullRequest");
            reqMUs.put(pullReq.getMessageId(), IPullRequest.class);
        }
        Collection<Receipt> receipts = (ArrayList<Receipt>)
                MessageContextUtils.getPropertyFromInMsgCtx(mc, MessageContextProperties.IN_RECEIPTS);
        if (receipts != null && !receipts.isEmpty()) {
            log.debug("Request message contained one or more Receipts signals");
            for(Receipt r : receipts)
                reqMUs.put(r.getMessageId(), IReceipt.class);
        }
        Collection<ErrorMessage> errors = (ArrayList<ErrorMessage>) 
                MessageContextUtils.getPropertyFromInMsgCtx(mc, MessageContextProperties.IN_ERRORS);
        if (errors != null && !errors.isEmpty()) {
            log.debug("Request message contained one or more Error signals");
            for(ErrorMessage e : errors)
                reqMUs.put(e.getMessageId(), IErrorMessage.class);
        }        
        
        return reqMUs;
    }
    
    /**
     * Helper method to change the processing state of the error signals that can not be included in the response
     * to {@link ProcessingStates#FAILURE}.
     * 
     * @param errors    The collection of {@link ErrorMessage}s that can not be included in the response.
     */
    private void setFailed(Collection<ErrorMessage> errors) {
        for(ErrorMessage e : errors) 
            try {
                log.debug("Set state of error [" + e.getMessageId() + "] to failed as it can not be included");
                MessageUnitDAO.setFailed(e);
                log.debug("Changed state of error [" + e.getMessageId() + "] to failed!");                
            } catch (DatabaseException dbe) {
                // Log and ignore error
                log.error("An error occured while changing the state of error [" + e.getMessageId() 
                            + "]! Details: " + dbe.getMessage());
            }
    }
}