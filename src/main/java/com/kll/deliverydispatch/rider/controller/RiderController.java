package com.kll.deliverydispatch.rider.controller;

import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.dto.NearbyRiderResponse;
import com.kll.deliverydispatch.rider.dto.RiderLocationRequest;
import com.kll.deliverydispatch.rider.service.RiderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/riders")
@RequiredArgsConstructor

public class RiderController {
    private final RiderService riderService;

    @PostMapping
    public Rider create(@RequestBody Rider rider){
        return riderService.create(rider);
    }

    @GetMapping("/{id}")
    public Rider getById(@PathVariable Long id){
        return riderService.getById(id);
    }

    @GetMapping
    public List<Rider>list(){
        return riderService.list();
    }

    @PutMapping("/{id}")
    public Rider update(@PathVariable Long id,@RequestBody Rider request){
        return riderService.update(id,request);
    }

    @PatchMapping("/{id}/disable")
    public Rider disable(@PathVariable Long id) {
        return riderService.disable(id);
    }

    @PutMapping("/{id}/location")
    public Rider updateLocation(@PathVariable Long id, @RequestBody RiderLocationRequest request) {
        return riderService.updateLocation(id, request);
    }

    @GetMapping("/nearby")
    public List<NearbyRiderResponse> findNearby(
            @RequestParam java.math.BigDecimal longitude,
            @RequestParam java.math.BigDecimal latitude,
            @RequestParam(defaultValue = "5") double radiusKm,
            @RequestParam(defaultValue = "20") int limit) {
        return riderService.findNearby(longitude, latitude, radiusKm, limit);
    }
}
