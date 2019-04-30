/*
 * Copyright 2015 Netflix, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.netflix.spinnaker.filters


import com.netflix.spinnaker.security.User
import groovy.util.logging.Slf4j
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import java.security.cert.X509Certificate

import static com.netflix.spinnaker.security.AuthenticatedRequest.*

@Slf4j
class AuthenticatedRequestFilter implements Filter {
  private static final String X509_CERTIFICATE = "javax.servlet.request.X509Certificate"

  /*
    otherName                       [0]
    rfc822Name                      [1]
    dNSName                         [2]
    x400Address                     [3]
    directoryName                   [4]
    ediPartyName                    [5]
    uniformResourceIdentifier       [6]
    iPAddress                       [7]
    registeredID                    [8]
  */
  private static final String RFC822_NAME_ID = "1"

  private final boolean extractSpinnakerHeaders
  private final boolean extractSpinnakerUserOriginHeader
  private final boolean forceNewSpinnakerRequestId

  public AuthenticatedRequestFilter(boolean extractSpinnakerHeaders = false,
                                    boolean extractSpinnakerUserOriginHeader = false,
                                    boolean forceNewSpinnakerRequestId = false) {
    this.extractSpinnakerHeaders = extractSpinnakerHeaders
    this.extractSpinnakerUserOriginHeader = extractSpinnakerUserOriginHeader
    this.forceNewSpinnakerRequestId = forceNewSpinnakerRequestId
  }

  @Override
  void init(FilterConfig filterConfig) throws ServletException {}

  @Override
  void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
    def spinnakerUser = null
    def spinnakerAccounts = null
    HashMap<String, String> otherSpinnakerHeaders = new HashMap<>()

    try {
      def session = ((HttpServletRequest) request).getSession(false)
      def securityContext = (SecurityContextImpl) session?.getAttribute("SPRING_SECURITY_CONTEXT")
      if (!securityContext) {
        securityContext = SecurityContextHolder.getContext()
      }
      def principal = securityContext?.authentication?.principal
      if (principal && principal instanceof User) {
        spinnakerUser = principal.username
        spinnakerAccounts = principal.allowedAccounts.join(",")
      }
    } catch (Exception e) {
      log.error("Unable to extract spinnaker user and account information", e)
    }

    if (extractSpinnakerHeaders) {
      def httpServletRequest = (HttpServletRequest) request
      spinnakerUser = spinnakerUser ?: httpServletRequest.getHeader(Header.USER.getHeader())
      spinnakerAccounts = spinnakerAccounts ?: httpServletRequest.getHeader(Header.ACCOUNTS.getHeader())

      Enumeration<String> headers = httpServletRequest.getHeaderNames()

      for (header in headers) {
        if (header.startsWith(Header.XSpinnakerPrefix)) {
          otherSpinnakerHeaders.put(header, httpServletRequest.getHeader(header))
        }
      }
    }
    if (extractSpinnakerUserOriginHeader) {
      otherSpinnakerHeaders.put(
        Header.USER_ORIGIN.getHeader(),
        "deck".equalsIgnoreCase(((HttpServletRequest) request).getHeader("X-RateLimit-App")) ? "deck" : "api"
      )
    }
    if (forceNewSpinnakerRequestId) {
      otherSpinnakerHeaders.put(
        Header.REQUEST_ID.getHeader(),
        UUID.randomUUID().toString()
      )
    }

    // only extract from the x509 certificate if `spinnakerUser` has not been supplied as a header
    if (request.isSecure() && !spinnakerUser) {
      ((X509Certificate[]) request.getAttribute(X509_CERTIFICATE))?.each {
        def emailSubjectName = it.getSubjectAlternativeNames().find {
          it.find { it.toString() == RFC822_NAME_ID }
        }?.get(1)

        spinnakerUser = emailSubjectName
      }
    }

    try {
      if (spinnakerUser) {
        MDC.put(Header.USER.getHeader(), spinnakerUser)
      }
      if (spinnakerAccounts) {
        MDC.put(Header.ACCOUNTS.getHeader(), spinnakerAccounts)
      }
      for (header in otherSpinnakerHeaders) {
        MDC.put(header.key, header.value)
      }

      chain.doFilter(request, response)
    } finally {
      MDC.clear()

      try {
        // force clear to avoid the potential for a memory leak if log4j is being used
        def log4jMDC = Class.forName("org.apache.log4j.MDC")
        log4jMDC.clear()
      } catch (Exception ignored) {}
    }
  }

  @Override
  void destroy() {}
}
