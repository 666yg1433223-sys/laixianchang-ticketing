package com.ticketing.booking.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.config.Bootstrap;
import com.ticketing.booking.service.PaymentService;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;

/** Local parent-owned fixture, fixed stored attempt ID; no HTTP result/control route. */
public final class PaymentRecoveryProcess {
    public static void main(String[] args) throws Exception {
        String run=args[0],mode=args[1];long attempt=Long.parseLong(args[4]);
        if(!run.matches("[a-z0-9-]{8,50}") || !Set.of("crash","recover").contains(mode) || attempt<=0) { throw new IllegalArgumentException(); }
        var runtime=TestRuntime.instance();runtime.reset();runtime.userA=Long.parseLong(args[2]);runtime.userB=Long.parseLong(args[3]);
        runtime.fixedTime=BookingHarness.BASE.plusSeconds(mode.equals("crash")?1:2);runtime.direct=false;runtime.recovery=mode.equals("recover");
        Path evidence=Bootstrap.ROOT.resolve("evidence/BIZ-03-I-001").resolve(run);var json=new ObjectMapper();
        if(mode.equals("crash")) runtime.hooks.put("P1_COMMITTED",id->{
            if(id!=attempt) { throw new IllegalStateException("Wrong fixture attempt"); }
            try {json.writeValue(evidence.resolve("Q10-P1-committed.json").toFile(),Map.of("attemptId",Long.toString(id),"pid",ProcessHandle.current().pid(),"point","physical P1 result+event commit before P2"));}
            catch(Exception e){Runtime.getRuntime().halt(73);}Runtime.getRuntime().halt(71);
        });
        CompletableFuture.delayedExecutor(45,TimeUnit.SECONDS).execute(()->Runtime.getRuntime().halt(72));
        try(var app=new SpringApplication(BookingApplication.class).run("--spring.profiles.active=biz01-it","--biz.runId="+run,"--biz.simulation.enabled=true")) {
            json.writeValue(evidence.resolve("Q10-"+mode+"-ready.json").toFile(),Map.of("pid",ProcessHandle.current().pid(),"port",18083));
            if(mode.equals("crash")) {app.getBean(PaymentService.class).generate(attempt);throw new IllegalStateException("Expected crash hook");}
            if(!"STOP".equals(new BufferedReader(new InputStreamReader(System.in)).readLine())) {throw new IllegalStateException("Parent shutdown missing");}
        }
    }
}
