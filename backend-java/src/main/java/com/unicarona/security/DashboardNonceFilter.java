package com.unicarona.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Set;
import com.unicarona.privacy.config.PrivacySettings;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** Serves current public contact context; the dashboard also gets a fresh payment-SDK nonce. */
public final class DashboardNonceFilter extends OncePerRequestFilter {
    private final SecureRandom random=new SecureRandom();
    private final PrivacySettings privacy;
    public DashboardNonceFilter(PrivacySettings privacy){this.privacy=privacy;}
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"GET".equals(request.getMethod()) || !Set.of("/dashboard.html","/legal.html","/login.html").contains(request.getRequestURI());
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        boolean dashboard="/dashboard.html".equals(request.getRequestURI());
        byte[] bytes=new byte[24];random.nextBytes(bytes);
        String nonce=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if(dashboard)request.setAttribute(ContentSecurityPolicy.NONCE_ATTRIBUTE,nonce);
        // Neither a stale contact nor a cached dashboard nonce may survive a new response context.
        var fresh=new HttpServletRequestWrapper(request) {
            private boolean conditional(String name) {return "If-Modified-Since".equalsIgnoreCase(name)||"If-None-Match".equalsIgnoreCase(name);}
            @Override public String getHeader(String name) {return conditional(name)?null:super.getHeader(name);}
            @Override public Enumeration<String> getHeaders(String name) {return conditional(name)?Collections.emptyEnumeration():super.getHeaders(name);}
            @Override public long getDateHeader(String name) {return conditional(name)?-1:super.getDateHeader(name);}
        };
        var cached=new ContentCachingResponseWrapper(response) {
            private boolean validator(String name){return "ETag".equalsIgnoreCase(name)||"Last-Modified".equalsIgnoreCase(name);}
            @Override public void setHeader(String name,String value){if(!validator(name))super.setHeader(name,value);}
            @Override public void addHeader(String name,String value){if(!validator(name))super.addHeader(name,value);}
            @Override public void setDateHeader(String name,long value){if(!validator(name))super.setDateHeader(name,value);}
            @Override public void addDateHeader(String name,long value){if(!validator(name))super.addDateHeader(name,value);}
        };
        try {
            chain.doFilter(fresh,cached);
            if(cached.getStatus()==200 && cached.getContentType()!=null && cached.getContentType().startsWith("text/html")) {
                String html=new String(cached.getContentAsByteArray(),StandardCharsets.UTF_8);
                String contact=HtmlUtils.htmlEscape(privacy.contactEmail());
                if(!contact.isBlank())html=html.replace("privacy@example.invalid",contact);
                int head=html.indexOf("</head>");
                if(head>=0) {
                    String meta=dashboard?"<meta name=\"unicarona-csp-nonce\" content=\""+nonce+"\">\n":"";
                    if(!contact.isBlank())meta+="<meta name=\"unicarona-privacy-contact\" content=\""+contact+"\">\n";
                    byte[] rendered=(html.substring(0,head)+meta+html.substring(head)).getBytes(StandardCharsets.UTF_8);
                    cached.resetBuffer();cached.getOutputStream().write(rendered);cached.setContentLength(rendered.length);
                    cached.setHeader("Cache-Control","no-store");
                }
            }
        } finally {cached.copyBodyToResponse();}
    }
}
