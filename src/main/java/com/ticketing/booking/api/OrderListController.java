package com.ticketing.booking.api;

import com.ticketing.booking.catalogue.CatalogueInput;
import com.ticketing.booking.service.OrderListingService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.ticketing.booking.api.CatalogueController.*;

@RestController
@Profile("local-sim")
public class OrderListController {
    private final OrderListingService service;
    public OrderListController(OrderListingService service) { this.service=service; }
    @GetMapping(value="/v1/orders",produces="application/json")
    public Map<String,Object> list(HttpServletRequest request) {
        parameters(request,Set.of("page","size"));
        return service.list(principal(request),CatalogueInput.page(request.getParameter("page"),1,1000),CatalogueInput.page(request.getParameter("size"),12,50));
    }
}
