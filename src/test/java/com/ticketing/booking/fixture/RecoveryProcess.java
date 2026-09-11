package com.ticketing.booking.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketing.booking.application.BookingApplication;
import com.ticketing.booking.config.Bootstrap;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;

/** Local supervised process only; there are no HTTP control endpoints. */
public final class RecoveryProcess {
    public static void main(String[] args) throws Exception {
        String run=args[0],mode=args[1];
        if(!run.matches("[a-z0-9-]{8,50}") || !(mode.equals("crash")||mode.equals("recover"))){throw new IllegalArgumentException("Local test arguments");}
        var runtime=TestRuntime.instance();runtime.reset();runtime.fixedTime=BookingHarness.BASE;
        runtime.userA=Long.parseLong(args[2]);runtime.userB=Long.parseLong(args[3]);
        Path evidence=Bootstrap.ROOT.resolve("evidence/BIZ-01-I-001").resolve(run);
        var json=new ObjectMapper();
        if(mode.equals("crash")){
            runtime.hooks.put("T1_COMMITTED",id->{
                try{json.writeValue(evidence.resolve("C07-crash-committed.json").toFile(),
                    Map.of("requestId",id,"pid",ProcessHandle.current().pid(),"point","after physical T1 commit, before T2","redisKeys",runtime.observedRedisKeys));}
                catch(Exception failure){Runtime.getRuntime().halt(73);}
                Runtime.getRuntime().halt(71);
            });
        }else{runtime.recovery=true;}
        CompletableFuture.delayedExecutor(45,TimeUnit.SECONDS).execute(()->Runtime.getRuntime().halt(72));
        try(var app=new SpringApplication(BookingApplication.class).run("--spring.profiles.active=biz01-it","--biz.runId="+run)){
            json.writeValue(evidence.resolve("C07-"+mode+"-ready.json").toFile(),Map.of("pid",ProcessHandle.current().pid(),"port",18083));
            String control=new BufferedReader(new InputStreamReader(System.in)).readLine();
            if(!"STOP".equals(control)){throw new IllegalStateException("Missing local shutdown instruction");}
        }
    }
}
