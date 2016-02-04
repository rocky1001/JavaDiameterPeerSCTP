/*
 * $Id: Acceptor.java,v 1.3 2011/06/02 10:06:28 qiwenyuan Exp $
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Iterator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.sun.nio.sctp.SctpChannel;
import com.sun.nio.sctp.SctpServerChannel;

import de.fhg.fokus.diameter.DiameterPeer.DiameterPeer;

/**
 * This class defines the Diameter Connection Acceptor.
 * 
 * <p>
 * A DiameterPeer may use several acceptors to get connections with other DiameterPeers. If a connection is created, it will be maintained
 * by a communicator.
 * 
 * @author Dragos Vingarzan vingarzan -at- fokus dot fraunhofer dot de
 * 
 */
public class Acceptor extends Thread {

    /** The logger */
    private static Log log = LogFactory.getLog(Acceptor.class);

    /** DiameterPeer API reference */
    private DiameterPeer diameterPeer;

    /** The acceptPort it listens to */
    private int acceptPort;

    /** The socket we are accepting connections on */
    private SctpServerChannel acceptSocket;

    /** The server address we are accepting connections on */
    private InetAddress acceptAddr;

    /** If it is accepting connections */
    private boolean accepting = false;

    /** Used to create the listening socket */
    private static int backlog = 50;

    /** SO_TIMEOUT for the socket */
    // private static int so_timeout=0;
    /**
     * Creates a new acceptor.
     * 
     * @param acceptPort
     *            Port number, at which the DiameterPeer is ready to accept a connection.
     */
    public Acceptor(int acceptPort) {
        this.acceptPort = acceptPort;
        this.acceptAddr = null;
        openSocket();
    }

    /**
     * Create a new acceptor.
     * 
     * @param acceptPort
     *            Port number, at which the DiameterPeer is ready to accept a connection.
     * @param bindAddr
     *            IP address, at which the DiameterPeer accepts connnection.
     * @param dp
     *            DiameterPeer, for which the acceptor is created.
     */
    public Acceptor(int port, InetAddress acceptAddr, DiameterPeer dp) {
        this.acceptPort = port;
        this.acceptAddr = acceptAddr;
        this.diameterPeer = dp;
        openSocket();
    }

    private void openSocket() {
        try {
            log.info("HSS server listening on local address<" + acceptAddr + ":" + acceptPort + ">");
            acceptSocket = SctpServerChannel.open();
            InetSocketAddress serverAddr = new InetSocketAddress(acceptAddr, acceptPort);
            acceptSocket.bind(serverAddr, backlog);

        } catch (IOException e) {
            log.error("Error opening socket on local address:<" + acceptAddr + ":" + acceptPort + "> !");
            log.error(e, e);
        }
    }

    private void closeSocket() {
        try {
            log.debug("Now closing sctpServerChannel:" + acceptSocket);
            acceptSocket.close();
            log.debug("Finished closing sctpServerChannel:" + acceptSocket);
        } catch (IOException e) {
            log.error("Error closing socket on local address:<" + acceptAddr + ":" + acceptPort + "> !");
            log.error(e, e);
        }
    }

    /**
     * Start a acceptor. A DiameterPeer is ready to accept a connection.
     * 
     */
    public void startAccepting() {
        accepting = true;
        this.start();
    }

    /**
     * Stop a acceptor. A DiameterPeer is unable to accept a connection.
     * 
     */
    public void stopAccepting() {
        accepting = false;
        closeSocket();
    }

    /**
     * Accept incoming request, create a communicator which will be associated with a propor Peer in the PeerManager.
     * 
     * @see java.lang.Runnable#run()
     */
    public void run() {
        SctpChannel sctpChannel;
        while (accepting) {
            try {
                log.debug("Start acceptting, waiting for remote peer to connect...");
                sctpChannel = acceptSocket.accept();
                log.debug("New local sctpChannel:" + sctpChannel + " for remote peer.");
            } catch (IOException e) {
                if (accepting) {
                    log.error("Acceptor: I/O Error on accept");
                    log.error(e, e);
                }
                break;
            }
            // get remote ip
            String remoteIp = "";
            Iterator<SocketAddress> i = null;
            try {
                i = (sctpChannel.getRemoteAddresses()).iterator();
                while (i.hasNext()) {
                    remoteIp = (((InetSocketAddress) i.next()).getAddress()).getHostAddress();
                    break;
                }
            } catch (IOException e) {
                log.error(e, e);
            }
            log.debug("Remote peer:" + remoteIp + " connectted !");
            Communicator comm = new Communicator(sctpChannel, diameterPeer, Communicator.Receiver, remoteIp);
            log.debug("New local Communicator:" + comm + " for above peer.");
        }
        closeSocket();
    }
}
