/*
 * Copyright (c) 2022-2023 Axonibyte Innovations, LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.axonibyte.lib.http.ws;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles WebSockets, primarily for the purposes of dispatching notifications.
 *
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
@WebSocket public class WSHandler implements Runnable {
  
  private static final Map<String, WSAction> actions = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private static final Deque<Entry<Session, JSONObject>> pending = new ArrayDeque<>();
  
  // so this would be like { Session, { "USER", [ usernameVar... ] }
  private static final Map<Session, Map<Object, Object>> sessionQualifierMap = new ConcurrentHashMap<>();
  
  // so this would be like { "USER", { usernameVar, [ Session... ] } }
  private static final Map<Object, Map<Object, Set<Session>>> qualifierSessionMap = new ConcurrentHashMap<>();
  
  private static Logger logger = LoggerFactory.getLogger(WSHandler.class);
  private static Thread instance = null;
  
  @OnWebSocketConnect public void onConnect(Session session) {
    if(null == instance) {
      instance = new Thread(this);
      instance.setDaemon(false);
      instance.start();
    }
    
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    logger.info("WebSocket connect from {}:{}", host, port);
    
    if(!sessionQualifierMap.containsKey(session))
      sessionQualifierMap.put(session, new ConcurrentHashMap<>());
  }
  
  @OnWebSocketClose public void onDisconnect(Session session, int statusCode, String reason) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    
    logger.info("WebSocket disconnect from {}:{}", host, port);
    
    Map<Object, Object> qualifiers = sessionQualifierMap.get(session);
    for(var qualifier : qualifiers.entrySet()) {
      var qualifierSessions = qualifierSessionMap.get(qualifier.getKey());
      if(null != qualifierSessions && qualifierSessions.containsKey(qualifier.getValue())) {
        qualifierSessions.remove(qualifier.getValue());
        if(qualifierSessions.isEmpty())
          qualifierSessionMap.remove(qualifier.getKey());
      }
    }
    
  }
  
  @OnWebSocketMessage public void onMessage(Session session, String message) {
    String host = session.getRemoteAddress().getHostString();
    int port = session.getRemoteAddress().getPort();
    logger.debug("WebSocket message received from {}:{}: \"{}\"", host, port, message);
    
    try {
      JSONObject request = new JSONObject(message);
      WSAction action = actions.get(request.getString("action"));
      
      if(null == action) return;
      JSONObject response = action.onMessage(session, request);
      
      if(null == response) return;
      synchronized(pending) {
        pending.addLast(new SimpleEntry<>(session, response));
        pending.notifyAll();
      }
      
    } catch(Exception e) {
      logger.error("Exception of type {} caused by {}:{}.", e.getClass().getName(), host, port);
    }
  }
  
  @Override public void run() {
    try {
      while(!instance.isInterrupted()) {
        Entry<Session, JSONObject> entry = null;
        
        synchronized(pending) {
          while(pending.isEmpty()) pending.wait();
          entry = pending.pop();
        }
        
        try {
          entry.getKey().getRemote().sendString(entry.getValue().toString());
        } catch(IOException e) {
          logger.error(
              "Some error occurred whilst processing the outgoing message: {}",
              null == e.getMessage() ? "no further info available" : e.getMessage());
        }
      }
    } catch(InterruptedException e) {
    }
  }
  
  /**
   * Dispatches a message to all sessions.
   *
   * @param message the message to be sent
   */
  public static void dispatch(JSONObject message) {
    var sessions = sessionQualifierMap.keySet();
    logger.debug("Queueing message for broadcast: {}", message.toString());
    synchronized(pending) {
      for(var session : sessions)
        pending.addLast(new SimpleEntry<>(session, message));
      pending.notifyAll();
    }
  }
  
  /**
   * Dispatches a message to a session or set of sessions scoped by some
   * qualifier and its respective value.
   *
   * @param qualifier the category that the value is associated with
   * @param value the value associated with the qualifier
   * @param message the message that needs to be sent
   */
  public static void dispatch(Object qualifier, Object value, JSONObject message) {
    var sessionMap = qualifierSessionMap.get(qualifier);
    if(null == sessionMap) return;
    
    var sessions = sessionMap.get(value);
    if(null == sessions) return;
    
    logger.debug("Queueing message for dispatch: {}", message.toString());
    synchronized(pending) {
      for(var session : sessions)
        pending.addLast(new SimpleEntry<>(session, message));
      pending.notifyAll();
    }
  }

  /**
   * Establishes a {@link WSAction} that can be used to process certain incoming
   * WebSocket messages.
   *
   * @param the {@link WSAction} to insert into the workflow
   */
  public static void putAction(WSAction action) {
    actions.put(action.getAction(), action);
  }
  
}
