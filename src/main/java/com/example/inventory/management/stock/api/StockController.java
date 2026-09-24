package com.example.inventory.management.stock.api;

import com.example.inventory.management.stock.command.StockCommandService;
import com.example.inventory.management.stock.command.dto.InboundRequest;
import com.example.inventory.management.stock.command.dto.OutboundRequest;
import com.example.inventory.management.stock.command.dto.StockChangeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stocks")
public class StockController implements StockApi {

    private final StockCommandService stockCommandService;

    public StockController(StockCommandService stockCommandService) {
        this.stockCommandService = stockCommandService;
    }

    @Override
    @PostMapping("/inbound")
    public StockChangeResponse inbound(@Valid @RequestBody InboundRequest request) {
        return stockCommandService.inbound(request);
    }

    @Override
    @PostMapping("/outbound")
    public StockChangeResponse outbound(@Valid @RequestBody OutboundRequest request) {
        return stockCommandService.outbound(request);
    }
}
