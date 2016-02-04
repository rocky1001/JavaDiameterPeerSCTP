/*
 * $Id: Communicator.java,v 1.4 2011/05/09 07:50:49 qiwenyuan Exp $
 * 
 * Copyright (C) 2004-2006 FhG Fokus
 * 
 * This file is part of Open IMS Core - an open source IMS CSCFs & HSS implementation
 * 
 * Open IMS Core is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License, or (at your option) any later version.
 * 
 * For a license to use the Open IMS Core software under conditions other than those described here, or to purchase support for this
 * software, please contact Fraunhofer FOKUS by e-mail at the following addresses: info@open-ims.org
 * 
 * Open IMS Core is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */

package de.fhg.fokus.diameter.DiameterPeer.transport;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.sun.nio.sctp.AbstractNotificationHandler;
import com.sun.nio.sctp.AssociationChangeNotification;
import com.sun.nio.sctp.HandlerResult;
import com.sun.nio.sctp.MessageInfo;
import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpStandardSocketOptions;
import com.sun.nio.sctp.ShutdownNotification;
import com.sun.nio.sctp.AssociationChangeNotification.AssocChangeEvent;

import de.fhg.fokus.diameter.DiameterPeer.DiameterPeer;
import de.fhg.fokus.diameter.DiameterPeer.data.AVP;
import de.fhg.fokus.diameter.DiameterPeer.data.Codec;
import de.fhg.fokus.diameter.DiameterPeer.data.DiameterMessage;
import de.fhg.fokus.diameter.DiameterPeer.data.DiameterMessageDecodeException;
import de.fhg.fokus.diameter.DiameterPeer.peer.Peer;
import de.fhg.fokus.diameter.DiameterPeer.peer.StateMachine;

/**
 * This class defines the Diameter Connection Receiver.
 * <p>
 * A communicator maintains a connection with a peer. After its creation, it will be managed by the PeerManager.
 * 
 * @author Dragos Vingarzan vingarzan -at- fokus dot fraunhofer dot de
 * 
 */
public class Communicator extends Thread {

    /** The logger */
    private static Log log = LogFactory.getLog(Communicator.class);

    /** DiameterPeer API reference */
    public DiameterPeer diameterPeer;

    /** peer it is comunicating for */
    public Peer peer = null;

    /** indicator if still active */
    private boolean running = false;

    /** Direction of socket opening */
    private int direction;
    public static final int Initiator = 0;
    public static final int Receiver = 1;

    /** socket connected to */
    public SctpChannel sctpChannel = null;
    
    private String remoteIp = "";

    /** max diameter message size (1024 * 100 = 100KB)*/
    private static int MAX_MESSAGE_LENGTH = 102400;
    
    /**ByteBuffer used to send message*/
    private static ByteBuffer sendByteBuffer = ByteBuffer.allocateDirect(MAX_MESSAGE_LENGTH);

    /**
     * Constructor giving the opened socket.
     * 
     * @param socket
     *            Socket should be opened.
     * @param dp
     *            DiameterPeer, which contains several Peers
     * @param direction
     *            1 for initiator, 0 for receiver
     */
    public Communicator(SctpChannel sc, DiameterPeer dp, int direction, String remoteIp) {
        this.sctpChannel = sc;
        try {
            log.debug("Now set SCTPSCTP_NODELAY = true and SCTP_DISABLE_FRAGMENTS = false .");
            sctpChannel.setOption(SctpStandardSocketOptions.SCTP_NODELAY, true);
            sctpChannel.setOption(SctpStandardSocketOptions.SCTP_DISABLE_FRAGMENTS, false);
        } catch (Exception e) {
            log.error("Error setting sctpChannel option.");
            log.error(e, e);
            return;
        }
        this.direction = direction;
        this.running = true;
        this.diameterPeer = dp;
        this.remoteIp = remoteIp;
        this.start();
    }

    /**
     * Constructor giving the opened socket.
     * 
     * @param socket
     *            Socket should be opened.
     * @param p
     *            Peer, for which the socket is opened.
     * @param direction
     *            1 for initiator, 0 for receiver.
     */
    public Communicator(SctpChannel sc, Peer p, int direction) {
        this.sctpChannel = sc;
        try {
            log.debug("Now set SCTPSCTP_NODELAY = true and SCTP_DISABLE_FRAGMENTS = false .");
            sctpChannel.setOption(SctpStandardSocketOptions.SCTP_NODELAY, true);
            sctpChannel.setOption(SctpStandardSocketOptions.SCTP_DISABLE_FRAGMENTS, false);
        } catch (Exception e) {
            log.error("Communicator: Error setting sctpChannel option.");
            log.error(e, e);
            return;
        }
        this.direction = direction;
        this.running = true;
        this.diameterPeer = p.diameterPeer;
        this.peer = p;
        this.start();
    }

    /**
     * Send a Diameter message.
     * 
     * @param msg
     *            The Diameter message which is sent.
     * @return true if successful, false otherwise.
     */
    public boolean sendMessage(DiameterMessage msg) {
        if (this.peer != null) {
            // to optimize the call and avoid critical zone
            // StateMachine.process(peer,StateMachine.Send_Message,msg,this);
            StateMachine.Snd_Message(peer, msg);
        }

        return sendDirect(msg);
    }

    /**
     * Send a Diameter message.
     * 
     * @param msg
     *            Diameter request which is sent.
     * @return true if successful, false otherwise.
     */
    public synchronized boolean sendDirect(DiameterMessage msg) {
        if (null == this.sctpChannel) {
            log.error("Send msg error, this sctpChannel is not connected !");
            return false;
        }
        sendByteBuffer.clear();
        log.debug("Sending msg using communicator:" + this + " and sctpChannel:" + sctpChannel);
        int sendBytesCount = 0;
        sendByteBuffer.put(Codec.encodeDiameterMessage(msg));
        sendByteBuffer.flip();
        MessageInfo messageInfo = MessageInfo.createOutgoing(null, 0);
        log.debug("Sending msg:\n" + msg);
        try {
            sendBytesCount = sctpChannel.send(sendByteBuffer, messageInfo);
        } catch (Exception e) {
            log.error("Error when sending the message! The sctpChannel:" + sctpChannel +" might be closed!");
            log.error(e, e);
            return false;
        }
        msg.networkTime = System.currentTimeMillis();
        sendByteBuffer.clear();
        log.debug("Finshed send the msg, length=" + sendBytesCount);
        log.error("Finshed send the msg, length=" + sendBytesCount);
        return true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    public void run() {
        MessageInfo messageInfo = null;
        ByteBuffer receiveByteBuffer = ByteBuffer.allocateDirect(MAX_MESSAGE_LENGTH);
        DiameterMessage msg = null;
        byte[] buffer = null;
        int len = 0;
        //handler to keep track of association setup and termination
        AssociationHandler assocHandler = new AssociationHandler();
        try {
            while (this.running) {
                messageInfo = sctpChannel.receive(receiveByteBuffer, System.out, assocHandler);
                log.debug("Received msg from communicator:" + this + " and sctpChannel:" + sctpChannel);
                log.debug("Received msg's length:" + messageInfo.bytes());
                log.error("Received msg's length:" + messageInfo.bytes());
                receiveByteBuffer.flip();

                if (receiveByteBuffer.remaining() > 0) {
                    buffer = new byte[messageInfo.bytes()];
                    receiveByteBuffer.get(buffer);
                    receiveByteBuffer.clear();
                    // log.debug("The origin message stream  is:\n" + CommonMethod.byteToHex(buffer));
                    //first we check the version
                    if (buffer[0] != 1) {
                        log.error("Expecting diameter version 1, received version " + buffer[0]);
                        continue;
                    }
                    //then we check the length of the message
                    len = ((int) buffer[1] & 0xFF) << 16 | ((int) buffer[2] & 0xFF) << 8 | ((int) buffer[3] & 0xFF);
                    if (len > MAX_MESSAGE_LENGTH) {
                        log.error("Message too long (msg length:" + len + " > max buffer length:" + MAX_MESSAGE_LENGTH + ").");
                        continue;
                    }
                    //now we can decode the message
                    try {
                        msg = Codec.decodeDiameterMessage(buffer, 0);
                    } catch (DiameterMessageDecodeException e) {
                        log.error("Error decoding diameter message !");
                        log.error(e, e);
                        msg = null;
                        continue;
                    }
                    msg.networkTime = System.currentTimeMillis();
                    log.debug("Received message is:\n" + msg);
                    if (this.peer != null) {
                        this.peer.refreshTimer();
                    }
                    processMessage(msg);
                }
                msg = null;
            }
        } catch (Exception e1) {
            log.error("Exception:"+ e1.getCause() +" catched in communicator:" + this + " and running flag=" + running);
            if (this.running) {
                if (this.peer != null) {
                    if (this.peer.I_comm == this) {
                        StateMachine.process(this.peer, StateMachine.I_Peer_Disc);
                    }
                    if (this.peer.R_comm == this) {
                        log.error("Now closing the peer:" + this.peer);
                        StateMachine.process(this.peer, StateMachine.R_Peer_Disc);
                    }
                }
                log.error("Error reading from sctpChannel:" + sctpChannel + ", the channel might be colsed.");

            }/* else it was a shutdown request, it's normal */
        }
        log.debug("Now closing communicator:" + this + ", and it's sctpChannel:" + sctpChannel);
        this.running = false;
        try {
            sctpChannel.close();
        } catch (IOException e) {
            log.error("Error closing sctpChannel !");
            log.error(e, e);
        }
    }

    private void processMessage(DiameterMessage msg) {
        /* pre-processing for special states */
        if (this.peer != null) {
            switch (this.peer.state) {
                case StateMachine.Wait_I_CEA:
                    if (msg.commandCode != DiameterMessage.Code_CE) {
                        StateMachine.process(this.peer, StateMachine.I_Rcv_Non_CEA, msg, this);
                        return;
                    }
                    break;
                case StateMachine.R_Open:
                    switch (msg.commandCode) {
                        case DiameterMessage.Code_CE:
                            if (msg.flagRequest) {
                                log.debug("Received CER msg when peer:" + peer + " in R_Open state !");
                                StateMachine.process(this.peer, StateMachine.R_Rcv_CER, msg, this);
                            } else {
                                log.debug("Received CEA msg when peer:" + peer + " in R_Open state !");
                                StateMachine.process(this.peer, StateMachine.R_Rcv_CEA, msg, this);
                            }
                            return;
                        case DiameterMessage.Code_DW:
                            if (msg.flagRequest) {
                                StateMachine.process(this.peer, StateMachine.R_Rcv_DWR, msg, this);
                            } else {
                                StateMachine.process(this.peer, StateMachine.R_Rcv_DWA, msg, this);
                            }
                            return;
                        case DiameterMessage.Code_DP:
                            if (msg.flagRequest) {
                                StateMachine.process(this.peer, StateMachine.R_Rcv_DPR, msg, this);
                            } else {
                                StateMachine.process(this.peer, StateMachine.R_Rcv_DPA, msg, this);
                            }
                            return;
                        default:
                            /* faster processing -> no state machine for regular messages */
                            // StateMachine.process(this.peer,StateMachine.R_Rcv_Message,msg,this);
                            StateMachine.Rcv_Process(this.peer, msg);
                            return;
                    }
                case StateMachine.I_Open:
                    switch (msg.commandCode) {
                        case DiameterMessage.Code_CE:
                            if (msg.flagRequest) {
                                StateMachine.process(this.peer, StateMachine.I_Rcv_CER, msg, this);
                            } else {
                                StateMachine.process(this.peer, StateMachine.I_Rcv_CEA, msg, this);
                            }
                            return;
                        case DiameterMessage.Code_DW:
                            if (msg.flagRequest) {
                                StateMachine.process(this.peer, StateMachine.I_Rcv_DWR, msg, this);
                            } else {
                                StateMachine.process(this.peer, StateMachine.I_Rcv_DWA, msg, this);
                            }
                            return;
                        case DiameterMessage.Code_DP:
                            if (msg.flagRequest) {
                                StateMachine.process(this.peer, StateMachine.I_Rcv_DPR, msg, this);
                            } else {
                                StateMachine.process(this.peer, StateMachine.I_Rcv_DPA, msg, this);
                            }
                            return;
                        default:
                            /* faster processing -> no state machine for regular messages */
                            // StateMachine.process(this.peer,StateMachine.I_Rcv_Message,msg,this);
                            StateMachine.Rcv_Process(this.peer, msg);
                            return;
                    }
            }
        }

        /* main processing */
        int event = 0;
        switch (msg.commandCode) {
            case DiameterMessage.Code_CE:
                if (msg.flagRequest) {
                    log.debug("Received CER msg when peer:" + peer + " in communicator:" + this);
                    /* CER - Special processing to find the peer */
                    /* find peer */
                    AVP fqdn = null;
                    AVP realm = null;
                    Peer p = null;
                    fqdn = msg.findAVP(AVP.Origin_Host, true, 0);
                    if (fqdn == null) {
                        log.error("Communicator: CER Received without Origin-Host");
                        return;
                    }
                    realm = msg.findAVP(AVP.Origin_Realm, true, 0);
                    if (realm == null) {
                        log.error("Communicator: CER Received without Origin-Realm");
                        return;
                    }
                    p = diameterPeer.peerManager.getPeerByFQDN(new String(fqdn.data));
                    if (p == null) {
                        p = diameterPeer.peerManager.addDynamicPeer(new String(fqdn.data), new String(realm.data), remoteIp);
                    }
                    if (p == null) {
                        // Give up
                        log.error("Communicator: Not Allowed to create new Peer");
                        return;
                    }
                    this.peer = p;
                    /* call state machine */
                    StateMachine.process(p, StateMachine.R_Conn_CER, msg, this);

                } else {
                    /* CEA */
                    if (this.peer == null) {
                        log.error("Received CEA for an unknown peer");
                        log.error(msg.toString());
                    } else {
                        if (this.direction == Initiator) {
                            event = StateMachine.I_Rcv_CEA;
                        } else {
                            event = StateMachine.R_Rcv_CEA;
                        }
                        StateMachine.process(this.peer, event, msg, this);
                    }
                }
                break;

            case DiameterMessage.Code_DW:
                if (msg.flagRequest) {
                    if (this.direction == Initiator)
                        event = StateMachine.I_Rcv_DWR;
                    else
                        event = StateMachine.R_Rcv_DWR;
                    StateMachine.process(peer, event, msg, this);
                } else {
                    if (this.direction == Initiator)
                        event = StateMachine.I_Rcv_DWA;
                    else
                        event = StateMachine.R_Rcv_DWA;
                    StateMachine.process(peer, event, msg, this);
                }
                break;

            case DiameterMessage.Code_DP:
                if (msg.flagRequest) {
                    if (this.direction == Initiator)
                        event = StateMachine.I_Rcv_DPR;
                    else
                        event = StateMachine.R_Rcv_DPR;
                    StateMachine.process(peer, event, msg, this);
                } else {
                    if (this.direction == Initiator)
                        event = StateMachine.I_Rcv_DPA;
                    else
                        event = StateMachine.R_Rcv_DPA;
                    StateMachine.process(peer, event, msg, this);
                }
                break;

            default:
                /*
                 * if (this.direction == Initiator) event = StateMachine.I_Rcv_Message; else event = StateMachine.R_Rcv_Message;
                 * StateMachine.process(peer,event,msg,this);
                 */
                /* faster processing -> no state machine for regular messages */
                StateMachine.Rcv_Process(this.peer, msg);
        }
    }

    /**
     * Shutdown the socket.
     */
    public void shutdown() {
        log.debug("Now doing shutdown the communicator:" + this);
        this.running = false;
        try {
            log.debug("Now doing shutdown the sctpChannel:" + sctpChannel);
            sctpChannel.close();
            log.debug("Finished shutdown the sctpChannel:" + sctpChannel);
        } catch (IOException e) {
            log.error("Error closing sctpChannel !");
            log.error(e,e);
        }
        log.debug("Finished shutdown the communicator:" + this);
    }

    class AssociationHandler extends AbstractNotificationHandler<PrintStream> {
        public HandlerResult handleNotification(AssociationChangeNotification notify, PrintStream stream) {
            if (notify.event().equals(AssocChangeEvent.COMM_UP)) {
                log.debug("New association come up with\ncommunicator:" + this + "\nsctpChannel:" + sctpChannel);
            }
            return HandlerResult.CONTINUE;
        }

        public HandlerResult handleNotification(ShutdownNotification notify, PrintStream stream) {
            log.debug("sctpChannel:" + sctpChannel + " now shutting down !");
            try {
                sctpChannel.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            return HandlerResult.RETURN;
        }
    }
}
/**
 * \package de.fhg.fokus.diameter.DiameterPeer.transport Contains acceptors and communicators for creating and maintaining connections
 * between Diameter peers.
 * 
 * <p>
 * A DiameterPeer uses an Acceptor to listen to requests from other DiameterPeers. If a request is coming in from an undetected
 * DiameterPeer, a Communicator is created to maintain this connection. This Communicator will also be associated with a Peer in the
 * PeerManager.
 */

