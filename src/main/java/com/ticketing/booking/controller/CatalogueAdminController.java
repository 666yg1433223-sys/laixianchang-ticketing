package com.ticketing.booking.controller;

import com.ticketing.booking.model.dto.*;
import com.ticketing.booking.model.vo.*;
import com.ticketing.booking.validation.InputValidation;

import com.ticketing.booking.catalogue.CatalogueInput;
import com.ticketing.booking.service.CatalogueService;
import com.ticketing.booking.domain.Values;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import static com.ticketing.booking.controller.CatalogueController.*;

@RestController
@Profile("local-sim")
@RequestMapping("/v1/admin")
public class CatalogueAdminController {
    private final CatalogueService service;
    public CatalogueAdminController(CatalogueService service) { this.service=service; }
    @GetMapping(value="/events",produces="application/json")
    public PageVO<EventDetailVO> list(HttpServletRequest request) {
        parameters(request,Set.of("page","size"));
        return service.adminEvents(principal(request),CatalogueInput.page(request.getParameter("page"),1,1000),CatalogueInput.page(request.getParameter("size"),10,20));
    }
    @PostMapping(value="/events",consumes="application/json",produces="application/json")
    public EventVO createEvent(HttpServletRequest request,@RequestBody String body) {
        parameters(request,Set.of());return service.createEvent(principal(request),body);
    }
    @PatchMapping(value="/events/{id}",consumes="application/json",produces="application/json")
    public EventVO editEvent(HttpServletRequest request,@PathVariable String id,@RequestBody String body) {
        parameters(request,Set.of());return service.editEvent(principal(request),Values.id(id),body);
    }
    @PostMapping(value="/sessions",consumes="application/json",produces="application/json")
    public SessionVO createSession(HttpServletRequest request,@RequestBody String body) {
        parameters(request,Set.of());return service.createSession(principal(request),body);
    }
    @PatchMapping(value="/sessions/{id}",consumes="application/json",produces="application/json")
    public SessionVO editSession(HttpServletRequest request,@PathVariable String id,@RequestBody String body) {
        parameters(request,Set.of());return service.editSession(principal(request),Values.id(id),body);
    }
    @PostMapping(value="/sessions/{id}/publish",consumes="application/json",produces="application/json")
    public SessionVO publish(HttpServletRequest request,@PathVariable String id,@RequestBody String body) { return state(request,id,"publish",body); }
    @PostMapping(value="/sessions/{id}/pause",consumes="application/json",produces="application/json")
    public SessionVO pause(HttpServletRequest request,@PathVariable String id,@RequestBody String body) { return state(request,id,"pause",body); }
    @PostMapping(value="/sessions/{id}/resume",consumes="application/json",produces="application/json")
    public SessionVO resume(HttpServletRequest request,@PathVariable String id,@RequestBody String body) { return state(request,id,"resume",body); }
    @PostMapping(value="/ticket-tiers",consumes="application/json",produces="application/json")
    public TierVO createTier(HttpServletRequest request,@RequestBody String body) {
        parameters(request,Set.of());return service.createTier(principal(request),body);
    }
    @PatchMapping(value="/ticket-tiers/{id}",consumes="application/json",produces="application/json")
    public TierVO editTier(HttpServletRequest request,@PathVariable String id,@RequestBody String body) {
        parameters(request,Set.of());return service.editTier(principal(request),Values.id(id),body);
    }
    private SessionVO state(HttpServletRequest request,String id,String action,String body) {
        parameters(request,Set.of());return service.state(principal(request),Values.id(id),action,body);
    }
}
