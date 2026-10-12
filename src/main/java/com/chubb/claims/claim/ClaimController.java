package com.chubb.claims.claim;

import com.chubb.claims.assignment.AssignmentService;
import com.chubb.claims.workflow.WorkflowService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/claims")
@Tag(name = "Claims", description = "Intent-based claim commands; all amounts are USD")
public class ClaimController {
  private final ClaimService claims;
  private final AssignmentService assignment;
  private final WorkflowService workflow;

  public ClaimController(
      ClaimService claims, AssignmentService assignment, WorkflowService workflow) {
    this.claims = claims;
    this.assignment = assignment;
    this.workflow = workflow;
  }

  @PostMapping
  public ResponseEntity<ClaimDtos.Detail> create(@Valid @RequestBody ClaimDtos.Create input) {
    var result = claims.create(input);
    return ResponseEntity.created(URI.create("/api/claims/" + result.id())).body(result);
  }

  @GetMapping("/{id}")
  public ClaimDtos.Detail get(@PathVariable UUID id) {
    return claims.get(id);
  }

  @GetMapping("/{id}/status")
  public ClaimDtos.StatusView status(@PathVariable UUID id) {
    return claims.status(id);
  }

  @GetMapping("/{id}/history")
  public List<ClaimService.HistoryView> history(@PathVariable UUID id) {
    return claims.history(id);
  }

  @GetMapping
  public ClaimDtos.ClaimPage list(
      @RequestParam(required = false) ClaimStatus status,
      @RequestParam(required = false) @Size(min = 1, max = 100) @Pattern(regexp = ".*\\S.*")
          String assignedOfficerId,
      @RequestParam(defaultValue = "false") boolean unassigned,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return claims.list(status, assignedOfficerId, unassigned, page, size);
  }

  @PostMapping("/{id}/assignment")
  public ClaimDtos.Detail assign(
      @PathVariable UUID id, @Valid @RequestBody ClaimDtos.Assignment input) {
    return assignment.assign(id, input);
  }

  @PostMapping("/{id}/review")
  public ClaimDtos.Detail review(
      @PathVariable UUID id, @Valid @RequestBody ClaimDtos.Version input) {
    return workflow.review(id, input);
  }

  @PostMapping("/{id}/information-requests")
  public ClaimDtos.Detail request(
      @PathVariable UUID id, @Valid @RequestBody ClaimDtos.RequestInformation input) {
    return workflow.request(id, input);
  }

  @PostMapping("/{id}/information-requests/{requestId}/response")
  public ClaimDtos.Detail provide(
      @PathVariable UUID id,
      @PathVariable UUID requestId,
      @Valid @RequestBody ClaimDtos.ProvideInformation input) {
    return workflow.provide(id, requestId, input);
  }

  @PostMapping("/{id}/assessment")
  public ClaimDtos.Detail assess(
      @PathVariable UUID id, @Valid @RequestBody ClaimDtos.Assessment input) {
    return workflow.assess(id, input);
  }

  @PostMapping("/{id}/settlement")
  public ClaimDtos.Detail settle(
      @PathVariable UUID id, @Valid @RequestBody ClaimDtos.Version input) {
    return workflow.settle(id, input);
  }

  @PostMapping("/{id}/rejection")
  public ClaimDtos.Detail reject(
      @PathVariable UUID id, @Valid @RequestBody ClaimDtos.Rejection input) {
    return workflow.reject(id, input);
  }
}
