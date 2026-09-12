package com.ticketing.booking.demo;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.domain.Values;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.util.Collections;
import org.springframework.web.filter.OncePerRequestFilter;

/** Test-classpath only. Old fixture credentials and foreign catalogue IDs cannot enter a demo. */
public final class DemoIdentityFilter extends OncePerRequestFilter {
    public interface Access {
        boolean demoAuthorization(String header);
        boolean demoBookingScope(String header,long session,long tier);
    }
    private final Access access;
    private final ObjectMapper json=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public DemoIdentityFilter(Access access) { this.access=access; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws IOException,ServletException {
        String auth=request.getHeader("Authorization");
        if(Collections.list(request.getHeaders("Authorization")).size()>1 || (auth!=null&&!access.demoAuthorization(auth))) {
            reject(response,401,"UNAUTHENTICATED");return;
        }
        if(auth!=null && request.getMethod().equals("POST") && request.getRequestURI().equals("/v1/booking-requests")) {
            byte[] bytes=request.getInputStream().readNBytes(8193);
            if(bytes.length>8192) { reject(response,400,"INVALID_ARGUMENT");return; }
            try {
                var body=json.readTree(bytes);
                if(body==null || !body.path("sessionId").isTextual() || !body.path("tierId").isTextual()) {
                    reject(response,400,"INVALID_ARGUMENT");return;
                }
                long session=Values.id(body.path("sessionId").asText()),tier=Values.id(body.path("tierId").asText());
                if(!access.demoBookingScope(auth,session,tier)) { reject(response,404,"RESOURCE_NOT_FOUND");return; }
            } catch(Exception invalid) { reject(response,400,"INVALID_ARGUMENT");return; }
            var wrapped=new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input=new ByteArrayInputStream(bytes);
                    return new ServletInputStream() {
                        public int read() { return input.read(); }
                        public boolean isFinished() { return input.available()==0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous demo requests only"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
            };
            chain.doFilter(wrapped,response);return;
        }
        chain.doFilter(request,response);
    }
    private static void reject(HttpServletResponse response,int status,String code) throws IOException {
        response.setStatus(status);response.setContentType("application/json");
        response.getWriter().write("{\"code\":\""+code+"\"}");
    }
}
