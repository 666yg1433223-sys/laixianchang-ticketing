package com.ticketing.booking.api;

import com.ticketing.booking.catalogue.CatalogueInput;
import com.ticketing.booking.catalogue.CatalogueService;
import com.ticketing.booking.domain.ApiFailure;
import com.ticketing.booking.domain.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-sim")
public class CatalogueController {
    private final CatalogueService service;
    public CatalogueController(CatalogueService service) { this.service=service; }
    @GetMapping(value="/v1/events",produces="application/json")
    public Map<String,Object> events(HttpServletRequest request) {
        parameters(request,Set.of("page","size"));
        return service.events(CatalogueInput.page(request.getParameter("page"),1,1000),CatalogueInput.page(request.getParameter("size"),12,50));
    }
    @GetMapping(value="/v1/events/{id}",produces="application/json")
    public Map<String,Object> event(HttpServletRequest request,@PathVariable String id) {
        parameters(request,Set.of());return service.event(Values.id(id));
    }
    @GetMapping(value="/v1/sessions/{id}/ticket-tiers",produces="application/json")
    public Map<String,Object> tiers(HttpServletRequest request,@PathVariable String id) {
        parameters(request,Set.of());return service.tiers(Values.id(id));
    }
    static void parameters(HttpServletRequest request,Set<String> allowed) {
        for(var entry:request.getParameterMap().entrySet()) {
            if(!allowed.contains(entry.getKey())||entry.getValue().length!=1) { throw CatalogueInput.invalid(); }
        }
    }
    static long principal(HttpServletRequest request) {
        if(!(request.getAttribute("booking.principal") instanceof Long id)||id<=0) { throw new ApiFailure(401,"UNAUTHENTICATED"); }
        return id;
    }
}
