package com.kll.deliverydispatch.rider.controller;

import com.kll.deliverydispatch.rider.entity.Rider;
import com.kll.deliverydispatch.rider.dto.NearbyRiderResponse;
import com.kll.deliverydispatch.rider.dto.RiderLocationRequest;
import com.kll.deliverydispatch.rider.dto.CreateRiderRequest;
import com.kll.deliverydispatch.rider.dto.UpdateRiderRequest;
import com.kll.deliverydispatch.rider.service.RiderService;
import com.kll.deliverydispatch.common.dto.PageResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/riders")
@RequiredArgsConstructor

public class RiderController {
    private final RiderService riderService;

    @PostMapping
    public Rider create(@Valid @RequestBody CreateRiderRequest request){
        return riderService.create(request.toEntity());
    }

    @GetMapping("/{id}")
    public Rider getById(@PathVariable Long id){
        return riderService.getById(id);
    }

    @GetMapping
    public List<Rider>list(){
        return riderService.list();
    }

    @GetMapping("/page")
    public PageResult<Rider> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return riderService.page(status, keyword, page, size);
    }

    @PutMapping("/{id}")
    public Rider update(@PathVariable Long id,@Valid @RequestBody UpdateRiderRequest request){
        return riderService.update(id,request.toEntity());
    }

    @PatchMapping("/{id}/disable")
    public Rider disable(@PathVariable Long id) {
        return riderService.disable(id);
    }

    @PatchMapping("/{id}/enable")
    public Rider enable(@PathVariable Long id) {
        return riderService.enable(id);
    }

    @PutMapping("/{id}/location")
    public Rider updateLocation(@PathVariable Long id, @Valid @RequestBody RiderLocationRequest request) {
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
