package com.sun.gi.framework.rawsocket.impl;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.gi.framework.rawsocket.RawSocketManager;
import com.sun.gi.framework.rawsocket.SimRawSocketListener;
import com.sun.gi.logic.SimTask;
import com.sun.gi.logic.Simulation;
import com.sun.gi.logic.SimTask.ACCESS_TYPE;
import com.sun.gi.logic.impl.GLOReferenceImpl;

/**
 * <p>Title: RawSocketManagerImpl </p>
 * 
 * <p>Description: A concrete implementation of <code>RawSocketManager</code>.
 * It listens for incoming data and processes socket connections and closures on 
 * a separate thread.  
 * 
 * NOTE: Data is sent on the caller's thread for ease of implementation and to keep the
 * thread count low.  This will work fine for small messages, but if typical use will 
 * have larger payloads, then the data should be sent on a different thread.</p>
 * 
 * <p>Copyright: Copyright (c) 2006</p>
 * <p>Company: Sun Microsystems, TMI</p>
 * 
 * @author	Sten Anderson
 * @version	1.0
 *
 */
public class RawSocketManagerImpl implements RawSocketManager {

	private int bufferSize = 512;
	private AtomicLong currentSocketID;
	private ConcurrentHashMap<Long, SocketInfo> socketMap;
	private ArrayList<Long> pendingConnections;			// list of socket IDs to be opened.
	private ArrayList<Long> pendingClosures;			// list of sockets IDs to be closed.
	private Selector selector;
	
	private boolean shouldUpdate = true;
	
	public RawSocketManagerImpl() {
		currentSocketID = new AtomicLong(0L);
		socketMap = new ConcurrentHashMap<Long, SocketInfo>();
		pendingConnections = new ArrayList<Long>();
		pendingClosures = new ArrayList<Long>();
		start();
	}
	
	// implemented methods from RawSocketManager
	
	/**
	 * Queues a socket to be opened at the given host on the given port.
	 * 
	 * NOTE: Right now, only reliable transport is supported.
	 */
	public long openSocket(long id, Simulation sim, ACCESS_TYPE access,
			long startObjectID, String host, int port, boolean reliable) {
		
		try {
			SocketChannel channel = SocketChannel.open();
			channel.configureBlocking(false);
			storeChannel(id,sim, access, startObjectID, channel);
			channel.connect(new InetSocketAddress(host, port));

			synchronized(pendingConnections) {
				pendingConnections.add(id);
			}
		}
		catch (IOException ioe) {
			generateExceptionEvent(id, ioe);
			return 0;
		}
		selector.wakeup();
		return id;
	}

	/**
	 * This method sends the given data down the socket mapped to the given ID.
	 * 
	 * NOTE: It does not return until all the data is drained from the buffer.
	 * Future versions may want to return immediately and send data on a different 
	 * thread to keep the caller lively.
	 * 
	 */
	public long sendData(long socketID, ByteBuffer data) {
		//System.out.println("Request to send: " + data.capacity() + " bytes");
		
		SocketChannel channel = getSocketChannel(socketID);
		if (channel == null) {
			return 0;
		}
		int totalWritten = 0;
		try {
			if (!channel.isConnected()) {
				generateExceptionEvent(socketID, new ClosedChannelException());
				return 0;
			}
			
			while (data.hasRemaining()) {
				totalWritten += channel.write(data);
			}
		}
		catch (IOException ioe) {
			generateExceptionEvent(socketID, ioe);
		}
		
		return totalWritten;
	}

	// see interface JDoc
	public void closeSocket(long socketID) {
		synchronized(pendingClosures) {
			if (!pendingClosures.contains(socketID)) {
				pendingClosures.add(socketID);
			}
		}
		selector.wakeup();
	}
	
	/**
	 * Returns the SocketChannel associated with the given socketID.
	 * 
	 * @param socketID		the socket ID
	 * 
	 * @return the Channel mapped to socketID
	 */
	private SocketChannel getSocketChannel(long socketID) {
		SocketInfo info = socketMap.get(socketID);
		if (info == null) {		// attempt to reference a channel that is no longer valid.
			System.out.println("No channel in map");
			return null;
		}
		return info.channel;
	}
	
	/**
	 * Generates and maps a SocketInfo object to an autogenerated socket ID.
	 * This ID can be used to reference the Socket for all future communication.
	 * 
	 * @param sim
	 * @param access
	 * @param gloID
	 * @param channel
	 * @return
	 */
	private void storeChannel(long key, Simulation sim, ACCESS_TYPE access, long gloID, SocketChannel channel) {		
		
		SocketInfo info = new SocketInfo(sim, access, gloID, channel);
		socketMap.put(key, info);
		
		
	}
	
	/**
	 * Starts the thread.
	 *
	 */
	private void start() {
		socketMap.clear();
		synchronized (pendingConnections) {
			pendingConnections.clear();
		}
		synchronized (pendingClosures) {
			pendingClosures.clear();
		}
		shouldUpdate = true;
		try {
			selector = Selector.open();
		}
		catch (IOException ioe) {
			ioe.printStackTrace();
		}
		
		Thread t = new Thread() {
			public void run() {
				while (shouldUpdate) {
					update();
				}
			}
		};
		t.start();
	}
	
	/**
	 * <p>This method is called repeatedly in a separate thread.  The idea here is that
	 * if all socket processing is done by one thread, it'll minimize concurrency issues, 
	 * although future versions could use a thread pool.
	 * 
	 * It does the following:</p>
	 * 
	 * <ul>
	 * <li>Attends to any I/O that's ready to be processed.</li>
	 * <li>Checks for, and attempts to complete, any pending connections.</li>
	 * <li>Checks for, and closes, any pending socket closure requests.</li>
	 * </ul>
	 *
	 */
	private void update() {
		long socketID = 0;	// If there's an exception thrown
							// this will be the offending socket.
							// The socket will be closed. 
		try {
			selector.select();		// block until some selectable I/O happens or wakeup() is called.
			
			Iterator keys = selector.selectedKeys().iterator();
			while (keys.hasNext()) {
				SelectionKey key = (SelectionKey) keys.next();
				keys.remove();
				socketID = (Long) key.attachment();
				SocketChannel curChannel = (SocketChannel) key.channel();
				if (key.isConnectable()) {
					if (curChannel.finishConnect()) {
						//System.out.println("socketID " + socketID + " has finished connecting.");
						key.interestOps(SelectionKey.OP_READ);
						generateEvent(socketID, "socketOpened", new Class[] {SimTask.class, long.class},
										new Object[] {socketID});

					}
				}
				else if (key.isReadable()) {
					if (curChannel.isConnected()) {
						ByteBuffer in = ByteBuffer.allocate(bufferSize);
						int numBytes = curChannel.read(in);
						in.flip();
						
						generateEvent(socketID, "dataReceived", 
								new Class[] {SimTask.class, long.class, ByteBuffer.class},
								new Object[] {socketID, in});
					}
				}
			}
			
			checkPendingConnections();
			checkPendingClosures();
		}
		catch (IOException ioe) {
			if (socketID > 0) {
				generateExceptionEvent(socketID, ioe);
				doSocketClose(socketID);
			}
			else {
				ioe.printStackTrace();
			}
		}
	}
	
	private void checkPendingConnections() {
		synchronized (pendingConnections) {
			for (int i = pendingConnections.size() - 1; i >= 0; i--) { 
				long curSocketID = pendingConnections.get(i);
				pendingConnections.remove(curSocketID);
				SocketChannel curChannel = socketMap.get(curSocketID).channel;
				SelectionKey key = null;
				try {
					key = curChannel.register(selector, SelectionKey.OP_CONNECT);
					key.attach(curSocketID);
						
				}
				catch (IOException ioe) {		// connection failed for some reason
					if (key != null) {
						key.cancel();
					}
					generateExceptionEvent(curSocketID, ioe);
				}
			}
		}
	}
	
	private void checkPendingClosures() {
		synchronized(pendingClosures) {
			for (int i = pendingClosures.size() - 1; i >= 0; i--) { 
				long curSocketID = pendingClosures.get(i);

				doSocketClose(curSocketID);
				pendingClosures.remove(curSocketID);
			}
		}
	}
	
	/**
	 * Attempts to close the given socket.  This will implicitly de-register its key with 
	 * the selector.
	 * 
	 * @param socketID
	 */
	private void doSocketClose(long socketID) {
		SocketChannel curChannel = socketMap.get(socketID).channel;
		if (curChannel == null) { 	// already closed.
			return;
		}
		try {
			if (curChannel.isConnected()) {
				curChannel.close();
				
				generateEvent(socketID, "socketClosed", 
							new Class[] {SimTask.class, long.class},
							new Object[] {socketID});
				socketMap.remove(socketID);
			}
		}
		catch (IOException ioe) {		// closure failed for some reason
			generateExceptionEvent(socketID, ioe);
		}
	}
	
	private void generateExceptionEvent(long socketID, IOException ioe) {
		generateEvent(socketID, "socketException", 
				new Class[] {SimTask.class, long.class, IOException.class},
				new Object[] {socketID, ioe});
		
	}
	
	private void generateEvent(long socketID, String callBack, Class[] params, Object[] args) {
		//System.out.println("begin generate event " + callBack + " " + socketID);
		SocketInfo info = socketMap.get(socketID);
		
		Method cbMethod = null;
		try {
			cbMethod = SimRawSocketListener.class.getMethod(callBack, params);
		} catch (SecurityException e1) {
			e1.printStackTrace();
		} catch (NoSuchMethodException e1) {
			e1.printStackTrace();
		}
		
		SimTask task = info.simulation.newTask(info.access, 
				new GLOReferenceImpl(info.gloID), cbMethod, args);
		
		info.simulation.queueTask(task);

	}
	
	private void stop() {
		shouldUpdate = false;
		if (selector == null) {
			return;
		}
		try {
			selector.close();
		}
		catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}
	
	private class SocketInfo {
		
		Simulation simulation;
		ACCESS_TYPE access;
		long gloID;
		SocketChannel channel;

		SocketInfo(Simulation sim, ACCESS_TYPE access, long gloID, SocketChannel channel) {
			this.simulation = sim;
			this.access = access;
			this.gloID = gloID;
			this.channel = channel;
		}
	}

	/* (non-Javadoc)
	 * @see com.sun.gi.framework.rawsocket.RawSocketManager#getNextSocketID()
	 */
	public long getNextSocketID() {
		// TODO Auto-generated method stub
		return currentSocketID.getAndIncrement();
	}

}
