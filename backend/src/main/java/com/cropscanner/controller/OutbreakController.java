package com.cropscanner.controller;

import com.cropscanner.service.OutbreakService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/outbreaks")
@CrossOrigin(origins = "${cropscanner.cors.allowed-origin:http://localhost:5173}")
public class OutbreakController {

    private final OutbreakService outbreakService;

    public OutbreakController(OutbreakService outbreakService) {
        this.outbreakService = outbreakService;
    }

    @GetMapping("/heatmap")
    public ResponseEntity<List<OutbreakService.HeatmapCell>> heatmap(
            @RequestParam(defaultValue = "30") int lookbackDays) {
        return ResponseEntity.ok(outbreakService.getSevereScanHeatmap(lookbackDays));
    }
}
