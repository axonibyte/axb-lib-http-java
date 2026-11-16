/*
 * Copyright (c) 2019 Axonibyte Innovations, LLC
 *
 * Licensed under the Blue Oak Model License 1.0.0.
 * See LICENSE for the full terms.
 */
package com.axonibyte.lib.http.rest;

import spark.Request;

/**
 * Some exception to be thrown on client or server error (PEBCAK-friendly).
 * 
 * @author Caleb L. Power
 */
public class EndpointException extends Exception {
  private static final long serialVersionUID = -260209318185659508L;
  private static final String INTERNAL_MESSAGE = "An EndpointException was thrown on %1$s by %2$s [HTTP %3$s Method]";
  
  private int errorCode = 500; // default HTTP error code is to indicate generic server error message, indicating unhandled exception error
  private String externalMessage = null;
  
  /**
   * Overloaded constructor to allow the specification of the error-prone endpoint.
   * 
   * @param request the request that threw this exception
   */
  public EndpointException(Request request) {
    super(String.format(INTERNAL_MESSAGE, request.pathInfo(), request.ip(), request.requestMethod()));
    externalMessage = getMessage();
  }
  
  /**
   * Overloaded constructor to specify the error-prone endpoint and some message.
   * 
   * @param request the request sent to the endpoint
   * @param message some custom informational exception message
   */
  public EndpointException(Request request, String message) {
    this(request);
    externalMessage = message;
  }
  
  /**
   * Overloaded constructor to specify the error-prone endpoint, some message,
   * and a custom HTTP error code.
   * 
   * @param request the request sent to the endpoint
   * @param message some custom informational exception message
   * @param errorCode relevant HTTP error code
   */
  public EndpointException(Request request, String message, int errorCode) {
    this(request, message);
    this.errorCode = errorCode;
  }
  
  /**
   * Overloaded constructor to specify the error-prone endpoint and some
   * rethrown Throwable.
   * 
   * @param request the request sent to the endpoint
   * @param cause the wrapped and rethrown exception
   */
  public EndpointException(Request request, Throwable cause) {
    this(request);
    initCause(cause);
  }
  
  /**
   * Overloaded constructor to specify the error-prone endpoint, some message, 
   * and some rethrown Throwable.
   * 
   * @param request the request sent to the endpoint
   * @param message some custom informational exception message
   * @param cause the wrapped and rethrown exception
   */
  public EndpointException(Request request, String message, Throwable cause) {
    this(request, cause);
    this.externalMessage = message;
  }
  
  /**
   * Overloaded constructor to specify the error-prone endpoint, some message,
   * some rethrown Throwable, and a custom HTTP response code.
   * 
   * @param request the request sent to the endpoint
   * @param message some custom informational exception message
   * @param errorCode some relevant HTTP response code
   * @param cause the wrapped and rethrown Throwable
   */
  public EndpointException(Request request, String message, int errorCode, Throwable cause) {
    this(request, message, cause);
    this.errorCode = errorCode;
  }
  
  /**
   * Retrieves the HTTP response code associated with the exception.
   * 
   * @return the relevant HTTP response (error) code
   */
  public int getErrorCode() {
    return errorCode;
  }
  
  /**
   * Retrieves the user-friendly message for endpoint responses.
   * Use {@link EndpointException#getMessage()} to retrieve the internal message.
   */
  @Override public String toString() {
    return externalMessage;
  }
  
}
