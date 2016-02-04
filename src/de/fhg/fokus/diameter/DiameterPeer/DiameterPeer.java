/*
 * $Id: DiameterPeer.java,v 1.3 2011/04/01 10:06:41 zhourumin Exp $
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

package de.fhg.fokus.diameter.DiameterPeer;

import java.io.IOException;
import java.io.StringReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Vector;
import java.util.concurrent.ArrayBlockingQueue;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import de.fhg.fokus.diameter.DiameterPeer.data.AVP;
import de.fhg.fokus.diameter.DiameterPeer.data.DiameterMessage;
import de.fhg.fokus.diameter.DiameterPeer.data.DiameterTask;
import de.fhg.fokus.diameter.DiameterPeer.peer.Application;
import de.fhg.fokus.diameter.DiameterPeer.peer.Peer;
import de.fhg.fokus.diameter.DiameterPeer.peer.PeerManager;
import de.fhg.fokus.diameter.DiameterPeer.peer.StateMachine;
import de.fhg.fokus.diameter.DiameterPeer.routing.RoutingEngine;
import de.fhg.fokus.diameter.DiameterPeer.transaction.TransactionListener;
import de.fhg.fokus.diameter.DiameterPeer.transaction.TransactionWorker;
import de.fhg.fokus.diameter.DiameterPeer.transport.Acceptor;

/**
 * This class defines the Peer Manager functionality
 * 
 * @author Dragos Vingarzan vingarzan -at- fokus dot fraunhofer dot de
 * 
 */
public class DiameterPeer {

    /** The logger */
    private static Log log = LogFactory.getLog(DiameterPeer.class);

    /** FQDN of itself */
    public String FQDN;

    /** Realm of itself */
    public String Realm;

    /** Vendor id of itself */
    public int Vendor_Id;

    /** Product Name of itself */
    public String Product_Name;

    /** Tc timer value - interval to attempt peer reconnect */
    public int Tc;

    /** Accepting sockets */
    public Vector<Acceptor> acceptors;

    /** Host ip addresses */
    public Vector<String> hostIpAdds;

    /** If unknown (unconfigured) peers are allowed to connect */
    public boolean AcceptUnknownPeers;

    /** If to delete unknown peers after they disconnect */
    public boolean DropUnknownOnDisconnect;

    /** Supported Applications */
    public Vector<Application> AuthApp, AcctApp;

    /** Routing Engine */
    public RoutingEngine routingTable = null;

    /** PeerManger manages connection with other Diameter peers */
    public PeerManager peerManager;

    /**
     * Sevice must define new eventListeners in order to process received Diameter answers
     */
    public Vector<EventListener> eventListeners;

    /** Received Queue Max Length */
    public int queueLength;

    /** Received Messages queue */
    public ArrayBlockingQueue<DiameterTask> queueTasks;

    /** workers for running the event listeners */
    private DiameterWorker workers[];

    /** The number of workers that process DiameterTasks from the taks queue */
    public int workerCount = 1;

    /** transaction worker */
    private TransactionWorker transactionWorker = null;

    /** Configuration DOM */
    Document config;

    /** Hop-by-Hop identifier */
    public int hopbyhop_id = 0;

    /** End-to-End identifier */
    public int endtoend_id = 0;

    /**
     * the local binding address
     */
    public String bindIp = "127.0.0.1";
    /**
     * the local binding address
     */
    public int bindPort = 3868;

    /**
     * the FQDN of the peer
     */
    public String peerFQDN;

    /**
     * the realm of the peer
     */
    public String peerRealm;

    /**
     * 请求超时时间
     */
    public long reqTimeout;

    /** Generates Hop-by-Hop id. */
    public synchronized int getNextHopByHopId() {
        return ++hopbyhop_id;
    }

    /** Generates End-to-End id. */
    public synchronized int getNextEndToEndId() {
        return ++endtoend_id;
    }

    /**
     * Construct a java bean friendly DiameterPeer (no args).
     */
    public DiameterPeer() {
        log.info("Bean style constructor called, don't forget to configure!");
    }

    /**
     * Construct a DiameterPeer based on a configuration file.
     * 
     * @param xml
     *            Configuration file.
     */
    public DiameterPeer(String xml, boolean isFile) {
        configure(xml, isFile);
        // enableTransactions(this.reqTimeout, 1);
    }

    /**
     * Configure a DiameterPeer based on a configuration file or string. Path && isFile==true is equivalent to DiameterPeer(Path).
     * 
     * @param xml
     *            XML configuration payload.
     * @param isFile
     *            Set to true if string is a path/uri, false if raw xml.
     */
    public void configure(String xml, boolean isFile) {
        Acceptor acc;
        NodeList nl;
        Node n, nv;
        int port, app_id, vendor_id;
        InetAddress addr;

        Application app;

        this.eventListeners = new Vector<EventListener>();

        java.util.Random rand = new java.util.Random();
        this.hopbyhop_id = rand.nextInt();
        this.endtoend_id = ((int) (System.currentTimeMillis() & 0xFFF)) << 20;
        this.endtoend_id |= rand.nextInt() & 0xFFFFF;

        if (isFile) {
            /* parse the config */
            if (!readConfigFile(xml)) {
                log.error("DiameterPeer: Error parsing config file");
                return;
            }
        } else {
            /* parse the config */
            if (!readConfigString(xml)) {
                log.error("DiameterPeer: Error parsing config String");
                return;
            }
        }

        this.FQDN = this.config.getDocumentElement().getAttribute("FQDN");
        this.Realm = this.config.getDocumentElement().getAttribute("Realm");
        this.Vendor_Id = Integer.parseInt(this.config.getDocumentElement().getAttribute("Vendor_Id"));
        this.Product_Name = this.config.getDocumentElement().getAttribute("Product_Name");
        this.AcceptUnknownPeers = Integer.parseInt(this.config.getDocumentElement().getAttribute("AcceptUnknownPeers")) != 0;
        this.DropUnknownOnDisconnect = Integer.parseInt(this.config.getDocumentElement().getAttribute("DropUnknownOnDisconnect")) != 0;
        this.Tc = Integer.parseInt(this.config.getDocumentElement().getAttribute("Tc"));
        this.workerCount = Integer.parseInt(this.config.getDocumentElement().getAttribute("Workers"));
        this.queueLength = Integer.parseInt(this.config.getDocumentElement().getAttribute("QueueLength"));
        this.queueTasks = new ArrayBlockingQueue<DiameterTask>(this.queueLength, true);
        this.reqTimeout = Long.parseLong(this.config.getDocumentElement().getAttribute("ReqTimeout"));

        log.info("FQDN: " + this.FQDN);
        log.info("Realm: " + this.Realm);
        log.info("Vendor_ID : " + this.Vendor_Id);
        log.info("Product Name: " + this.Product_Name);
        log.info("AcceptUnknwonPeers: " + this.AcceptUnknownPeers);
        log.info("DropUnknownOnDisconnect: " + this.DropUnknownOnDisconnect);
        log.info("ReqTimeout: " + this.reqTimeout);

        startWorkers();

        /* Read Supported Application ids */
        this.AuthApp = new Vector<Application>();
        this.AcctApp = new Vector<Application>();
        this.hostIpAdds = new Vector<String>();
        // get auth data from DiameterPeerHSS.xml.env-vm
        nl = this.config.getDocumentElement().getElementsByTagName("Auth");
        for (int i = 0; i < nl.getLength(); i++) {
            n = nl.item(i);
            app_id = 0;
            app_id = Integer.parseInt(n.getAttributes().getNamedItem("id").getNodeValue());
            vendor_id = 0;
            if (n.getAttributes().getNamedItem("vendor") != null)
                vendor_id = Integer.parseInt(n.getAttributes().getNamedItem("vendor").getNodeValue());

            app = new Application(app_id, vendor_id, Application.Auth);
            this.AuthApp.add(app);
        }
        // get acct data from DiameterPeerHSS.xml.env-vm
        nl = this.config.getDocumentElement().getElementsByTagName("Acct");
        for (int i = 0; i < nl.getLength(); i++) {
            n = nl.item(i);
            app_id = 0;
            app_id = Integer.parseInt(n.getAttributes().getNamedItem("id").getNodeValue());
            vendor_id = 0;
            if (n.getAttributes().getNamedItem("vendor") != null)
                vendor_id = Integer.parseInt(n.getAttributes().getNamedItem("vendor").getNodeValue());

            app = new Application(app_id, vendor_id, Application.Acct);
            this.AcctApp.add(app);
        }

        /* Initialize the Peer Manager */
        this.peerManager = new PeerManager(this);

        /* Read the peers from the configuration file */
        nl = this.config.getDocumentElement().getElementsByTagName("Peer");
        for (int i = 0; i < nl.getLength(); i++) {
            n = nl.item(i);

            String localIp = n.getAttributes().getNamedItem("LocalIp").getNodeValue();

            int localPort = Integer.parseInt(n.getAttributes().getNamedItem("LocalPort").getNodeValue());

            this.peerFQDN = n.getAttributes().getNamedItem("FQDN").getNodeValue();

            this.peerRealm = n.getAttributes().getNamedItem("Realm").getNodeValue();

            port = 3868;
            nv = n.getAttributes().getNamedItem("port");
            if (nv == null) {
                port = 3868;
            } else {
                port = Integer.parseInt(nv.getNodeValue());
            }

            this.peerManager.configurePeer(localIp, localPort, this.peerFQDN, this.peerRealm, "", port);
        }

        /* Create & start connection acceptors */
        this.acceptors = new Vector<Acceptor>();
        nl = this.config.getDocumentElement().getElementsByTagName("Acceptor");
        for (int i = 0; i < nl.getLength(); i++) {
            n = nl.item(i);

            nv = n.getAttributes().getNamedItem("port");
            if (nv != null) {
                this.bindPort = Integer.parseInt(nv.getNodeValue());
            }

            addr = null;
            nv = n.getAttributes().getNamedItem("bind");
            if (nv != null && nv.getNodeValue().length() != 0)
                try {
                    this.bindIp = nv.getNodeValue();
                    this.hostIpAdds.add(this.bindIp);
                    addr = InetAddress.getByName(this.bindIp);
                } catch (UnknownHostException e) {
                    log.error("DiameterPeer: Can not resolve " + this.bindIp);
                    e.printStackTrace();
                    continue;
                }
            acc = new Acceptor(bindPort, addr, this);
            acc.startAccepting();
            this.acceptors.add(acc);
        }

        // get host ip address data from DiameterPeerHSS.xml.env-vm
        nl = this.config.getDocumentElement().getElementsByTagName("HostIpAdd");
        for (int i = 0; i < nl.getLength(); i++) {
            n = nl.item(i);
            String hostIpAddress = null;
            hostIpAddress = n.getAttributes().getNamedItem("ip").getNodeValue();
            this.hostIpAdds.add(hostIpAddress);
        }

        initRoutingTable(this.config);

        this.peerManager.start();

    }

    private boolean readConfigFile(String cfgFile) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // factory.setValidating(true);
        // factory.setNamespaceAware(true);
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            config = builder.parse(cfgFile);
        } catch (SAXException sxe) {
            // Error generated during parsing)
            Exception x = sxe;
            if (sxe.getException() != null)
                x = sxe.getException();
            x.printStackTrace();
            return false;
        } catch (ParserConfigurationException pce) {
            // Parser with specified options can't be built
            pce.printStackTrace();
            return false;
        } catch (IOException ioe) {
            // I/O error
            ioe.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean readConfigString(String cfgString) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // factory.setValidating(true);
        // factory.setNamespaceAware(true);
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            config = builder.parse(new InputSource(new StringReader(cfgString)));
        } catch (SAXException sxe) {
            // Error generated during parsing)
            Exception x = sxe;
            if (sxe.getException() != null)
                x = sxe.getException();
            x.printStackTrace();
            return false;
        } catch (ParserConfigurationException pce) {
            // Parser with specified options can't be built
            pce.printStackTrace();
            return false;
        } catch (IOException ioe) {
            // I/O error
            ioe.printStackTrace();
            return false;
        }
        return true;
    }

    /* configure routing table */
    private void initRoutingTable(Document config) {
        NodeList nl, nlc;
        String fqdn, realm;
        int metric;

        this.routingTable = new RoutingEngine();
        nl = config.getDocumentElement().getElementsByTagName("DefaultRoute");
        for (int i = 0; i < nl.getLength(); i++) {
            fqdn = nl.item(i).getAttributes().getNamedItem("FQDN").getNodeValue();
            metric = Integer.valueOf(nl.item(i).getAttributes().getNamedItem("metric").getNodeValue());
            routingTable.addDefaultRoute(fqdn, metric);
        }
        nl = config.getDocumentElement().getElementsByTagName("Realm");
        for (int i = 0; i < nl.getLength(); i++) {
            realm = nl.item(i).getAttributes().getNamedItem("name").getNodeValue();
            nlc = nl.item(i).getChildNodes();
            for (int j = 0; j < nlc.getLength(); j++)
                if (nlc.item(j).getNodeName().equalsIgnoreCase("Route")) {
                    fqdn = nlc.item(j).getAttributes().getNamedItem("FQDN").getNodeValue();
                    metric = Integer.valueOf(nlc.item(j).getAttributes().getNamedItem("metric").getNodeValue());
                    routingTable.addRealmRoute(realm, fqdn, metric);
                }
        }

    }

    private void startWorkers() {
        workers = new DiameterWorker[workerCount];
        for (int i = 0; i < workerCount; i++)
            workers[i] = new DiameterWorker(i, this.queueTasks);
    }

    /**
     * Bundles Diameter request and answer to a transaction.
     * 
     * @param timeout
     *            Lifetime of a transaction.
     * @param checkInterval
     */
    public void enableTransactions(long timeout, long checkInterval) {
        if (this.transactionWorker == null)
            this.transactionWorker = new TransactionWorker(this, timeout, checkInterval);
    }

    /**
     * Creates a new Diameter request with proxiable bit set.
     * 
     * @param command_code
     *            Command code of the request.
     * @param application_id
     *            Application id in the message header.
     * @return Created Diameter request.
     */
    public DiameterMessage newRequest(int command_code, int application_id) {
        return newRequest(command_code, true, application_id);
    }

    /**
     * Creates a new Diameter request.
     * 
     * @param command_code
     *            Command code of the request.
     * @param Proxiable
     *            Proxiable is set if true.
     * @param application_id
     *            Application id in the message header.
     * @return Created Diameter request.
     */
    public DiameterMessage newRequest(int command_code, boolean Proxiable, int application_id) {
        DiameterMessage msg = new DiameterMessage(command_code, true, Proxiable, application_id, this.getNextHopByHopId(), this
                .getNextEndToEndId());
        AVP avp;

        avp = new AVP(AVP.Origin_Host, true, 0);
        avp.setData(this.FQDN);
        msg.addAVP(avp);

        avp = new AVP(AVP.Origin_Realm, true, 0);
        avp.setData(this.Realm);
        msg.addAVP(avp);

        return msg;
    }

    /**
     * Creates a new Diameter answer.
     * 
     * @param request
     *            Diameter request.
     * @return Diameter anwer; null, if fails.
     */
    public DiameterMessage newResponse(DiameterMessage request) {
        DiameterMessage msg = new DiameterMessage(request.commandCode, false, request.applicationID);
        AVP avp;

        msg.endToEndID = request.endToEndID;
        msg.hopByHopID = request.hopByHopID;

        if (request.getSessionId() != null) {
            avp = request.getSessionId();
            msg.addAVP(avp);
        }

        avp = new AVP(AVP.Origin_Host, true, 0);
        avp.setData(this.FQDN);
        msg.addAVP(avp);

        avp = new AVP(AVP.Origin_Realm, true, 0);
        avp.setData(this.Realm);
        msg.addAVP(avp);

        return msg;
    }

    /**
     * Sends a Diameter message to the designated peer.
     * 
     * @param peerFQDN
     *            FQDN of the designated peer.
     * @param msg
     *            Diameter message should be sent.
     * @return true, if the message is sent successfully.
     */
    public boolean sendMessage(String peerFQDN, DiameterMessage msg) {
        Peer p;
        p = peerManager.getPeerByFQDN(peerFQDN);
        if (p == null) {
            log.error("DiameterPeer: Peer " + peerFQDN + " not found in peer list.");
            return false;
        }

        if (p.state != StateMachine.I_Open && p.state != StateMachine.R_Open) {

            log.error("DiameterPeer: Peer(state=" + p.state + ") " + peerFQDN + " not connected.");
            return false;
        }
        return p.sendMessage(msg);
    }

    /**
     * Sends a Diameter message to a realm routed peer.
     * 
     * @param msg
     *            Diameter message should be sent.
     * @return true, if the message is sent successfully.
     */
    public boolean sendMessage(DiameterMessage msg) {
        Peer p;
        if (routingTable == null) {
            log.error("DiameterPeer: RoutingTable not initialized!");
            return false;
        }
        p = routingTable.getRoute(msg, peerManager);
        if (p == null) {
            log.error("DiameterPeer: No suitable peer to route to could be found.");
            return false;
        }
        return p.sendMessage(msg);
    }

    /**
     * Sends a Diameter request and a TransactionWorker handles the answer.
     * 
     * @param peerFQDN
     *            FQDN of the peer.
     * @param req
     *            Diameter request.
     * @param tl
     *            TransactionListener.
     * @return true of a message is sent successfully, otherwise false.
     */
    public boolean sendRequestTransactional(String peerFQDN, DiameterMessage req, TransactionListener tl) {
        if (this.transactionWorker != null)
            return transactionWorker.sendRequestTransactional(peerFQDN, req, tl);
        else {
            log.error("DiameterPeer:sendRequestTransactional(): Transactions are not enabled on this peer!");
            return false;
        }
    }

    /**
     * Sends a Diameter request and a TransactionWorker handles the answer.
     * 
     * @param req
     *            Diameter request.
     * @param tl
     *            TransactionListener.
     * @return true of a message is sent successfully, otherwise false.
     */
    public boolean sendRequestTransactional(DiameterMessage req, TransactionListener tl) {
        if (this.transactionWorker != null)
            return transactionWorker.sendRequestTransactional(req, tl);
        else {
            log.error("DiameterPeer:sendRequestTransactional(): Transactions are not enabled on this peer!");
            return false;
        }
    }

    /**
     * Sends a Diameter message to the designated peer. The thread will wait for a Diameter answer until it arrives or timeout.
     * 
     * @param peerFQDN
     *            FQDN of the designated peer.
     * @param req
     *            Diameter message should be sent.
     * @return The Diameter answer returned. Null if timeout.
     */
    public DiameterMessage sendRequestBlocking(String peerFQDN, DiameterMessage req) {
        if (this.transactionWorker != null)
            return transactionWorker.sendRequestBlocking(peerFQDN, req);
        else {
            log.error("DiameterPeer:sendRequestBlocking(): Transactions are not enabled on this peer!");
            return null;
        }
    }

    /**
     * Sends a Diameter message to a realm routing determined peer. The thread will wait for a Diameter answer until it arrives or timeout.
     * 
     * @param req
     *            Diameter message should be sent.
     * @return The Diameter answer returned. Null if timeout.
     */
    public DiameterMessage sendRequestBlocking(DiameterMessage req) {
        if (this.transactionWorker != null)
            return transactionWorker.sendRequestBlocking(req);
        else {
            log.error("DiameterPeer:sendRequestBlocking(): Transactions are not enabled on this peer!");
            return null;
        }
    }

    /**
     * Adds an EventListener.
     * 
     * @param l
     *            EventListener added.
     */
    public void addEventListener(EventListener l) {
        eventListeners.add(l);
    }

    /**
     * Removes an EventListener.
     * 
     * @param l
     *            EventListener removed.
     */
    public void removeEventListener(EventListener l) {
        eventListeners.remove(l);
    }

    /**
     * Deactives the DiameterPeer.
     */
    public void shutdown() {
        Acceptor acc;
        Iterator<Acceptor> i = acceptors.iterator();
        while (i.hasNext()) {
            acc = i.next();
            acc.stopAccepting();
        }
        peerManager.shutdown();
        for (int j = 0; j < workerCount; j++)
            workers[j].shutdown();
        if (transactionWorker != null)
            transactionWorker.shutdown();
    }
}
/**
 * 
 * \mainpage The Open IMS Core JavaDiameterPeer
 * 
 * Documentation for Open IMS Core Java Diameter Peer library.
 * 
 * The JavaDiameterPeer, like its counterpart CDiamterPeer, is a smart and an efficient Java implementation of the Diameter Base Protocol
 * (IETF RFC3588). It provides a convenient way to use Diameter stack in a Java environment and can easily extend network nodes implemented
 * in Java with a Diameter interface. It is used at the HSS (FhoSS) in the Open IMS core network.
 * 
 * <h1>Design and Implementation</h1> The following figure depicts the structure of the JavaDiameterPeer.
 * <p>
 * <div align="center"> <img src="../img/jdiameterpeer_design.jpg" alt="jdiameterpeer_design.jpg"> </div>
 * 
 * <p>
 * A DiameterPeer represents a Diameter node which implements the Diameter protocol and acts either as a Client or as a Server. The most
 * important component of a DiameterPeer is a PeerManager. The PeerManager manages a set of Peers. Each Peer has a Communicator, which
 * maintains a Diameter connection. The Peer is implemented based on the RFC 3588, section 5 and contains a state machine defined in that
 * part. Peers managed by the PeerManager can be configured by a configuration file. They can also be detected and added to the PeerManager
 * in runtime dynamically.
 * 
 * <p>
 * Since a DiameterPeer is listening to certain ports by using a set of Acceptors, the incoming Diameter Capabilities-Exchange-Request
 * messages can be noticed. Upon receiving such a request, a new Peer with a communicator will be created and added to the PeerManager.
 * 
 * <p>
 * Outgoing Diameter messages and incoming Diameter messages are handled by the JavaDiameterPeer differently. For an outgoing Diameter
 * request, JavaDiameterPeer sends this message to the PeerManager directly. The PeerManager will find out a suitable Peer for sending it.
 * 
 * <p>
 * An incoming Diameter message received by a communicator is pushed in a TaskQueue at first. This TaskQueue is a FIFO blocking queue. As
 * soon as the message is available in the queue, a DiameterWorker will take it out of the queue and deliver it to a set of event listeners
 * defined by the user.
 * 
 * <p>
 * Typically, a Diameter client sends a Diameter message to a Diameter server directly and receives a Diameter answer by adapting an
 * EventListener. A Diameter server, however, defines only an EventListener to process incoming Diameter request.
 * 
 * <p>
 * In order to provide a convenient way to handle Diameter request and answer at the client side, a TransactionWorker is defined. A
 * TransactionWorker groups a request with its corresponding answer. By using TransactionWorker, a user needs not to take care of mapping
 * requests with answers.
 * 
 * <h1>Peer Configuration</h1>
 * DiameterPeer.xml provides an example of how a Diameter peer is configured.
 * 
 * <pre>
 * &lt;xml version=&quot;1.0&quot; encoding=&quot;UTF-8&quot;?&gt;
 * &lt;DiameterPeer 
 * 	FQDN=&quot;localhost&quot;
 * 	Realm=&quot;open-ims.org&quot;
 * 	Vendor_Id=&quot;10415&quot;
 * 	Product_Name=&quot;JavaDiameterPeer&quot;
 * 	AcceptUnknownPeers=&quot;1&quot;
 * 	DropUnknownOnDisconnect=&quot;1&quot;
 * 	Tc=&quot;10&quot;
 * 	Workers=&quot;8&quot;
 * 	QueueLength=&quot;32&quot;
 * &gt;
 * 	&lt;Acceptor acceptPort=&quot;3868&quot; bind=&quot;127.0.0.1&quot; /&gt;
 * 	
 * 	&lt;Auth id=&quot;16777216&quot; vendor=&quot;10415&quot;/&gt;
 * 	&lt;Auth id=&quot;16777216&quot; vendor=&quot;0&quot; /&gt;
 * 	&lt;Acct id=&quot;16777216&quot; vendor=&quot;0&quot; /&gt;
 * &lt;/DiameterPeer&gt;
 * </pre>
 * 
 * <h1>To do</h1>
 * Currently, the JavaDiameterPeer supports only peer connection. The authentication/authorization portion and accounting portion as defined
 * in the Diameter base protocol (IFC 3588 section 8, 9) are still not implemented, since Cx, Sh do not require user session support
 * actually. However, auth and acct session support are needed to realize Ro interface (auth session) and Rf (acct session) interface.
 * 
 * Realm routing (because this is not yet supported, when using you will have to specify each time the FQDN of the destination host. This is
 * usually a configuration parameter other the modules using this one). A Realm routing process specified in the RFC 3588 section 2.7 is
 * still in the development stage.
 * 
 * 
 * <h1>The Open IMS Core</h1>
 * The <b>Home-Page</b> of the Open Source IMS Core project is at <a href="http://www.open-ims.org/">http://www.open-ims.org/</a>
 * 
 * <p>
 * The <b>Development</b> is taking place at <a href="http://developer.berlios.de/projects/openimscore/">
 * http://developer.berlios.de/projects/openimscore/</a>
 * 
 * <p>
 * <div align="center"> <img src="../img/osims.jpg" alt="osims.jpg"> </div>
 * 
 * <p>
 * <dl>
 * <dt><b>Author:</b></dt>
 * <dd>Dragos Vingarzan vingarzan -at- fokus dot fraunhofer dot de</dd>
 * <p>
 * <dd>Shengyao Chen shc -at- fokus dot fraunhofer dot de</dd>
 * <p>
 * 
 * <dt><b>Attention:</b></dt>
 * <dd>It has to be noted that this <font color=red><b> Open Source IMS Core System is not intended to become or act as a product in a
 * commercial context!</b></font> Its sole purpose is to provide an IMS core reference implementation for IMS technology testing and IMS
 * application prototyping for research purposes, typically performed in IMS test-beds.
 * <p>
 * Users of the Open Source IMS Core System have to be aware that IMS technology may be subject of patents and license terms, as being
 * specified within the various IMS-related IETF, ITU-T, ETSI, and 3GPP standards. Thus all Open IMS Core users have to take notice of this
 * fact and have to agree to check out carefully before installing, using and extending the Open Source IMS Core System, if related patents
 * and licenses may become applicable to the intended usage context.
 * </dd>
 * 
 * <dt><b>Note:</b></dt>
 * <dd>Copyright (C) 2004-2006 FhG Fokus
 * <p>
 * The Open IMS Core is an open source IMS CSCFs &amp; HSS implementation.
 * <p>
 * Open IMS Core is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published
 * by the Free Software Foundation; either version 2 of the License, or (at your option) any later version.
 * <p>
 * For a license to use the Open IMS Core software under conditions other than those described here, or to purchase support for this
 * software, please contact Fraunhofer FOKUS by e-mail at the following addresses: <a href="mailto:info@open-ims.org">info@open-ims.org</a>
 * <p>
 * Open IMS Core is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * </dd>
 * </dl>
 */

