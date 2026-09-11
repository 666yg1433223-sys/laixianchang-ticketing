package com.ticketing.booking.integration;

import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.fixture.TestRuntime;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import static org.junit.jupiter.api.Assertions.*;

class StartupBoundaryTest {
    private static void assertPortFree() throws Exception {
        try(var socket=new ServerSocket()) {socket.setReuseAddress(false);socket.bind(new InetSocketAddress("127.0.0.1",18083));}
    }
    @Test void realContextFailsBeforeMigrationAndHttp() throws Exception {
        assertPortFree();
        String run=System.getProperty("biz.runId","biz01-startup-boundary");
        var outcomes=new ArrayList<Map<String,Object>>();
        for(String mode:List.of("missing-profile","formal-profile","wrong-database","wrong-account","wrong-port","missing-identity")){
            var args=new ArrayList<String>();args.add("--biz.runId="+run);
            if(!mode.equals("missing-profile")){args.add("--spring.profiles.active="+(mode.equals("formal-profile")?"biz01":"biz01-it"));}
            if(mode.equals("wrong-database")){args.add("--biz.db.name=not_authorized");}
            if(mode.equals("wrong-account")){args.add("--biz.db.user=not_authorized");}
            if(mode.equals("wrong-port")){args.add("--server.port=18084");}
            TestRuntime.enabled=!mode.equals("missing-identity");
            try{
                assertThrows(Exception.class,()->{
                    try(var unexpected=new SpringApplication(BookingApplication.class).run(args.toArray(String[]::new))){fail("unsafe startup succeeded");}
                });
                assertPortFree();outcomes.add(Map.of("case",mode,"startupRejected",true,"port18083Free",true));
            }finally{TestRuntime.enabled=true;}
        }
        Path evidence=Bootstrap.ROOT.resolve("evidence/BIZ-01-I-001").resolve(run);Files.createDirectories(evidence);
        new com.fasterxml.jackson.databind.ObjectMapper().writeValue(evidence.resolve("startup-negative-contexts.json").toFile(),outcomes);
    }
}
