package com.example.inventory.management.stock;

import com.example.inventory.management.stock.dto.InboundRequest;
import com.example.inventory.management.stock.dto.OutboundRequest;
import com.example.inventory.management.stock.dto.StockChangeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stocks")
public class StockController implements StockApi {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    @Override
    @PostMapping("/inbound")
    public StockChangeResponse inbound(@Valid @RequestBody InboundRequest request) {
        return stockService.inbound(request);
    }

    @Override
    @PostMapping("/outbound")
    public StockChangeResponse outbound(@Valid @RequestBody OutboundRequest request) {
        return stockService.outbound(request);
    }
}
