/*
 * Copyright (c) 2020-2023 Axonibyte Innovations, LLC. All rights reserved.
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
package com.axonibyte.lib.http;

import static spark.Spark.before;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.staticFiles;
import static spark.Spark.stop;
import static spark.Spark.webSocket;

import java.util.concurrent.atomic.AtomicReference;

import com.axonibyte.lib.http.rest.Endpoint;
import com.axonibyte.lib.http.rest.HTTPMethod;
import com.axonibyte.lib.http.ws.WSHandler;

import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import spark.Route;

/**
 * API Driver; manages RESTful and WebSocket API jSONEndpoints.
 * 
 * @author Caleb L. Power <cpower@axonibyte.com>
 */
public class APIDriver implements Runnable {

  private static final AtomicReference<APIDriver> instance = new AtomicReference<>();
  private static final String RESPONDER_STATIC_FOLDER = ".";
  private static final String WEBSOCKET_ROUTE = "/v1/stream";

  private final Logger logger = LoggerFactory.getLogger(APIDriver.class);

  public static APIDriver getInstance() {
    return instance.get();
  }
  
  public static void setInstance(APIDriver driver) {
    instance.set(driver);
  }
  
  private int port; // the port that the front end should run on
  private Endpoint endpoints[] = null; // the pages that will be accessible
  private String allowedOrigins = null; // the allowed origins for CORS
  private Thread thread = null; // the thread to run the frontend
  
  /**
   * Opens the specified external port so as to launch the front end.
   * 
   * @param port the port by which the front end will be accessible
   * @param allowedOrigins the allowed origins for CORS
   * @param endpoints a varargs object of endpoints to hook into the API driver
   */
  private APIDriver(int port, String allowedOrigins, Endpoint... endpoints) {
    this.allowedOrigins = allowedOrigins;
    this.port = port;
    this.endpoints = endpoints;
    
    staticFiles.location(RESPONDER_STATIC_FOLDER); // relative to the root of the classpath
  }

  /**
   * Runs the front end in a separate thread so that it can be halted externally.
   */
  @Override public void run() {
    webSocket("/stream", WSHandler.class); // initialize websocket

    logger.info("Exposing API on port {}.", port);
    port(port);
    
    before((req, res) -> {
      res.header("Access-Control-Allow-Origin", allowedOrigins);
      res.header("Access-Control-Allow-Methods", "DELETE, POST, GET, PATCH, PUT, OPTIONS");
      res.header(
          "Access-Control-Allow-Headers",
          "Content-Type, Access-Control-Allow-Headers, Access-Control-Allow-Origin, Access-Control-Allow-Methods, Authorization, X-Requested-With");
      res.header("Access-Control-Expose-Headers", "Content-Type, Content-Length");
      res.header("Content-Type", "application/json"); 
    });

    options("*", (req, res)-> {
      String accessControlRequestHeaders = req.headers("Access-Control-Request-Headers");
      if(accessControlRequestHeaders != null)
        res.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
      
      String accessControlRequestMethod = req.headers("Access-Control-Request-Method");
      if(accessControlRequestMethod != null)
        res.header("Access-Control-Allow-Methods", accessControlRequestMethod);

      return "OK";
    });
    
    // iterate through initialized pages and determine the appropriate HTTP request types
    for(Endpoint endpoint : endpoints)
      for(HTTPMethod method : endpoint.getHTTPMethods())
        method.getSparkMethod().accept(endpoint.getRoute(), endpoint::onRequest);
    
    // this is a patch because the WebSocket route overrides Spark.notFound
    final Route route = (req, res) -> {
      if(!req.raw().getPathInfo().equals(WEBSOCKET_ROUTE)) {
        logger.info(
            "User at {} attempted to hit nonexistent endpoint {} {}",
            req.ip(),
            req.requestMethod(),
            req.raw().getRequestURI());
        res.type("application/json");
        res.status(404);
        return new JSONObject()
        .put("status", "error")
        .put("info", "Resource not found.")
            .toString(2) + '\n';
      }

      return null;
    };
    
    for(var method : HTTPMethod.values())
      for(var version : APIVersion.values())
        method.getSparkMethod().accept(version.versionize("*"), route);
  }
  
  /**
   * Stops the web server.
   */
  public void halt() {
    stop();
  }
  
  /**
   * Builds the frontend and launches it in a thread.
   * 
   * @param port the listening port
   * @param allowedOrigins the allowed origins for CORS
   * @param endpoints a varargs object of endpoints to hook into the API driver
   * @return the newly-operating API driver
   */
  public static APIDriver build(int port, String allowedOrigins, Endpoint... endpoints) {
    APIDriver apiDriver = new APIDriver(port, allowedOrigins, endpoints);
    apiDriver.thread = new Thread(apiDriver);
    apiDriver.thread.setDaemon(false);
    apiDriver.thread.start();
    return apiDriver;
  }
  
}
