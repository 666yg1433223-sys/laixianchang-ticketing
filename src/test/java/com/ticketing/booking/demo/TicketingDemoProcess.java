package com.ticketing.booking.demo;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Local private-file control channel, not an HTTP administration backdoor. */
public final class TicketingDemoProcess {
    private TicketingDemoProcess() { }
    public static void main(String[] args) {
        Path directory=null;DemoSession session=null;String runId="",nonce="",stopCommandId="";boolean success=false;
        try {
            if(args.length!=1) throw new IllegalArgumentException("EXPECTED_RUN_ID");
            runId=args[0];directory=DemoFiles.directory(runId);
            var launch=DemoFiles.read(directory.resolve("launch.json"));
            nonce=launch.path("nonce").asText();
            if(!runId.equals(launch.path("runId").asText())||!nonce.matches("[a-f0-9]{64}")
                    ||Files.exists(directory.resolve("ready.json"))||Files.exists(directory.resolve("stopped.json")))
                throw new IllegalStateException("INVALID_OR_USED_DEMO_LAUNCH");
            session=new DemoSession(runId);
            var primary=session.fixture("interactive",5);
            session.verifyPriorFactsUnchanged();
            var self=ProcessHandle.current();
            DemoFiles.write(directory.resolve("ready.json"),Map.of("runId",runId,"nonce",nonce,"pid",self.pid(),
                "startedAt",self.info().startInstant().orElseThrow().toString(),"javaPath",self.info().command().orElseThrow(),
                "baseUrl","http://127.0.0.1:18083","status","READY","fixture",primary,"evidencePath",session.evidence().toString()));
            boolean scenariosRun=false;Set<String> consumed=new HashSet<>();
            while(true) {
                Path commandFile=directory.resolve("command.json");
                if(Files.exists(commandFile,LinkOption.NOFOLLOW_LINKS)) {
                    var command=DemoFiles.read(commandFile);
                    String id=command.path("commandId").asText(),action=command.path("action").asText();
                    validateCommand(runId,nonce,command.path("runId").asText(),command.path("nonce").asText(),id,action);
                    if(consumed.add(id)) {
                        if(action.equals("STOP")) { stopCommandId=id;success=true;break; }
                        if(scenariosRun) {
                            Files.delete(commandFile);
                            DemoFiles.write(directory.resolve("result-"+id+".json"),Map.of("runId",runId,"commandId",id,"success",false,"summary","SCENARIOS_ALREADY_RUN_USE_NEW_RUN_ID","evidencePath",session.evidence().toString()));
                        } else {
                            scenariosRun=true;boolean passed=false;String summary;
                            try {
                                var result=DemoScenarios.run(session);session.verifyPriorFactsUnchanged();
                                passed=Boolean.TRUE.equals(result.get("success"));
                                summary=passed?"交易场景通过，模拟支付，无真实资金；本轮数据已保留。":"SCENARIO_ASSERTION_FAILED";
                                session.save("scenarios.json",result);
                            } catch(Throwable failure) {
                                summary="SCENARIO_FAILED:"+failure.getClass().getSimpleName();
                                session.save("process-scenario-failure.json",Map.of("success",false,"errorType",failure.getClass().getSimpleName(),"fixtureDataRetained",true));
                            } finally {session.h.runtime.hooks.clear();session.setTime(null);session.plan("SUCCESS",100);}
                            Files.delete(commandFile);
                            DemoFiles.write(directory.resolve("result-"+id+".json"),Map.of("runId",runId,"commandId",id,"success",passed,"summary",summary,"evidencePath",session.evidence().toString()));
                            if(!passed) throw new IllegalStateException("SCENARIO_FAILED_STOPPED_TO_PRESERVE_FACTS");
                        }
                        if(Files.exists(commandFile)) Files.delete(commandFile);
                    }
                }
                session.driveOnce();Thread.sleep(200);
            }
        } catch(Throwable failure) {
            if(directory!=null) {
                try {DemoFiles.write(directory.resolve("startup-failure.json"),Map.of("runId",runId,"status","FAILED","errorType",failure.getClass().getSimpleName(),"at",Instant.now().toString()));}
                catch(Exception cannotSave) {System.err.println("DEMO_FAILURE_RECORD_UNAVAILABLE");}
            }
            System.err.println("DEMO_FAILED: environment or assertions unavailable; private local evidence retained");
        } finally {
            if(session!=null) try {session.close();} catch(Throwable failure) {success=false;System.err.println("DEMO_CLOSE_CHECK_FAILED");}
            if(directory!=null) try {
                DemoFiles.write(directory.resolve("stopped.json"),Map.of("runId",runId,"nonce",nonce,"commandId",stopCommandId,"status",success?"STOPPED":"FAILED","pid",ProcessHandle.current().pid(),"at",Instant.now().toString(),"dataRetained",true));
                if(!stopCommandId.isEmpty()) Files.deleteIfExists(directory.resolve("command.json"));
            }catch(Exception ignored){success=false;}
        }
        System.exit(success?0:1);
    }
    static void validateCommand(String expectedRun,String expectedNonce,String run,String nonce,String id,String action) {
        if(!expectedRun.equals(run) || !MessageDigest.isEqual(expectedNonce.getBytes(java.nio.charset.StandardCharsets.US_ASCII),nonce.getBytes(java.nio.charset.StandardCharsets.US_ASCII))
            || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || !Set.of("STOP","SCENARIOS").contains(action)) throw new IllegalArgumentException("INVALID_DEMO_COMMAND");
    }
}
