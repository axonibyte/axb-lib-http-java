/*
 * Copyright (c) 2020 Axonibyte Innovations, LLC
 *
 * Licensed under the Blue Oak Model License 1.0.0.
 * See LICENSE for the full terms.
 */
package com.axonibyte.lib.http.rest;

import java.util.function.BiConsumer;

import spark.Route;

/**
 * HTTP request methods.
 * 
 * @author Caleb L. Power
 */
public enum HTTPMethod {
  
  /**
   * HTTP DELETE method.
   */
  DELETE(spark.Spark::delete),
  
  /**
   * HTTP GET method.
   */
  GET(spark.Spark::get),
  
  /**
   * HTTP PATCH method.
   */
  PATCH(spark.Spark::patch),
  
  /**
   * HTTP POST method.
   */
  POST(spark.Spark::post),
  
  /**
   * HTTP PUT method.
   */
  PUT(spark.Spark::put);
  
  private BiConsumer<String, Route> sparkMethod = null;
  
  private HTTPMethod(BiConsumer<String, Route> sparkMethod) {
    this.sparkMethod = sparkMethod;
  }
  
  /**
   * Retrieves the Spark method associated with this HTTPMethod.
   * 
   * @return the appropriate BiConsumer
   */
  public BiConsumer<String, Route> getSparkMethod() {
    return sparkMethod;
  }
  
}
