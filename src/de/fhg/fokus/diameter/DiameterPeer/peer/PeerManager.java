/*
 * $Id: PeerManager.java,v 1.2 2011/04/01 10:05:57 zhourumin Exp $
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

package de.fhg.fokus.diameter.DiameterPeer.peer;

import java.util.Iterator;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import de.fhg.fokus.diameter.DiameterPeer.DiameterPeer;

/**
 * This class defines the Peer Manager functionality
 * 
 * @author Dragos Vingarzan vingarzan -at- fokus dot fraunhofer dot de
 * 
 */
public class PeerManager extends Thread {

    /** The logger */
    private static Log log = LogFactory.getLog(PeerManager.class);

    /** DiameterPeer API reference */
    public DiameterPeer diameterPeer;

    /** List of peers */
    public Vector<Peer> peers;

    /** if it is watching peers for activity */
    public boolean running = true;

    /**
     * Constructor a PeerManager for a DiameterPeer. A PeerManager managers peers, to which this DiameterPeer maintains connections. The
     * number of peers maintained by the PeerManager can be configured in a configure file. New detected peers during a CER/CEA procedure
     * can also be managed by this class, if tag AcceptUnknownPeers is > 0.
     * 
     * @param dp
     *            DiameterPeer containing the PeerManager.
     */
    public PeerManager(DiameterPeer dp) {
        this.diameterPeer = dp;
        this.peers = new Vector<Peer>();
    }

    /**
     * Configure a Peer according to the configuration parameters in a configure file and add it to the Peer list.
     * 
     * @param fqdn
     *            Fully Qualified Domain Name of the Peer.
     * @param realm
     *            Realm name of a Peer.
     * @param acceptPort
     *            acceptPort number.
     * @return The configured Peer.
     */
    public Peer configurePeer(String localIp, int localPort, String fqdn, String realm, String remoteIp, int port) {
        log.debug("Now adding remote peer <" + fqdn + ":" + port + "> into PeerManager.");
        Peer p;
        p = new Peer(localIp, localPort, fqdn, realm, remoteIp, port);
        p.diameterPeer = this.diameterPeer;
        p.lastReceiveTime = System.currentTimeMillis() - (diameterPeer.Tc * 1000);
        log.debug("Peer:" + p + " now added into PeerManager.");
        if (0 == port) {
            log.debug("HSS works as SERVER part in above peer !");
        } else {
            log.debug("HSS works as CLIENT part in above peer !");
        }
        peers.add(p);
        return p;
    }

    /**
     * Search for a Peer in the Peer list according to its FQDN.
     * 
     * @param fqdn
     *            Fully Qualified Domain Name of a Peer.
     * @return The Peer found, otherwise null.
     */
    public Peer getPeerByFQDN(String fqdn) {
        Peer p;
        //		
        // Iterator i = peers.iterator();
        // while(i.hasNext()){
        // p = (Peer) i.next();
        // if (p.FQDN.equalsIgnoreCase(fqdn))
        // return p;
        // }
        for (int i = 0; i < peers.size(); i++) {
            p = peers.get(i);
            if (p.FQDN.equalsIgnoreCase(fqdn))
                return p;
        }
        return null;
    }

    /**
     * Add Peers detected dynamicly to the Peer list, if it is allowed.
     * 
     * @param fqdn
     *            Fully Qualified Domain Name of a Peer.
     * @param realm
     *            Realm name of a Peer.
     * @return The configured Peer, otherwise null.
     */
    public Peer addDynamicPeer(String fqdn, String realm, String remoteIp) {
        Peer p;
        if (!diameterPeer.AcceptUnknownPeers) {
            log.error("PeerManager: Sorry " + fqdn + " but we don't accept unknown peers.");
            return null;
        }
        // TODO check if it exists already

        p = configurePeer("", 0, fqdn, realm, remoteIp, 0);
        p.isDynamicPeer = true;
        return p;
    }

    /**
     * PeerManager thread maintains Peers in the Peer list.
     * 
     * @see java.lang.Runnable#run()
     */
    public void run() {
        int i;
        Peer p;
        long expiration;
        running = true;
        while (running) {
            expiration = System.currentTimeMillis() - (diameterPeer.Tc * 1000);

            for (i = 0; i < peers.size(); i++) {
                //log.debug("There is(are):" + peers.size() + " peer(s) in peerManager.");
                p = peers.get(i);
                if (p.lastReceiveTime < expiration) {
                    switch (p.state) {
                        /* initiating connection */
                        case StateMachine.Closed:
                            if (p.isDynamicPeer && diameterPeer.DropUnknownOnDisconnect) {
                                log.error("the peer is dynamic peer and in closed state, now remove it:" + p.FQDN);
                                peers.remove(i);
                                i--;
                                break;
                            }
                            log.info("Connecting to peer " + p.FQDN + " dynamic " + p.isDynamicPeer + " dropping "
                                    + diameterPeer.DropUnknownOnDisconnect);
                            p.refreshTimer();
                            StateMachine.process(p, StateMachine.Start);
                            break;
                        /* timeouts */
                        case StateMachine.Wait_Conn_Ack:
                        case StateMachine.Wait_I_CEA:
                        case StateMachine.Closing:
                        case StateMachine.Wait_Returns:
                            p.refreshTimer();
                            StateMachine.process(p, StateMachine.Timeout, null);
                            break;
                        /* inactivity detected */
                        case StateMachine.I_Open:
                        case StateMachine.R_Open:
                            log.debug("Now peer:" + p + " is in R_Open state.");
                            if (p.waitingDWA) {
                                log.error("Waiting for " + diameterPeer.Tc + " seconds, and DID NOT received any DWA.");
                                p.waitingDWA = false;
                                if (p.state == StateMachine.I_Open) {
                                    StateMachine.process(p, StateMachine.I_Peer_Disc);
                                }
                                if (p.state == StateMachine.R_Open) {
                                    p.isNormal = false;
                                    log.debug("Maintain peer:" + p + " in R_Open state!");
                                    //StateMachine.process(p, StateMachine.R_Peer_Disc);
                                }
                            } else {
                                log.debug("Now send DWR msg on peer:" + p );
                                p.waitingDWA = true;
                                if (!StateMachine.Snd_DWR(p)) {
                                    log.error("Error when sending DWR msg on peer:" + p + " , the sctpChannel in peer's communicator might be closed !");
                                    log.error("Now closing the peer:" + p);
                                    if (p.state == StateMachine.I_Open) {
                                        StateMachine.process(p, StateMachine.I_Peer_Disc);
                                    }
                                    if (p.state == StateMachine.R_Open) {
                                        p.isNormal = false;
                                        StateMachine.process(p, StateMachine.R_Peer_Disc);
                                    }
                                } else {
                                    log.debug("Finished send DWR msg on peer:" + p + " and now refreshed timer.");
                                    p.refreshTimer();
                                }
                            }
                            break;
                        /* ignored states */
                        /* unknown states */
                        default:
                            log.error("PeerManager: Peer " + p.FQDN + " inactive  in state " + p.state);
                    }
                }
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Shut down the PeerManager.
     */
    public void shutdown() {
        Iterator<Peer> i;
        Peer p;
        this.running = false;
        i = peers.iterator();
        while (i.hasNext()) {
            p = i.next();
            // if (p.I_comm!=null) p.I_comm.shutdown();
            // if (p.R_comm!=null) p.R_comm.shutdown();
            StateMachine.process(p, StateMachine.Stop);
        }
    }
}
