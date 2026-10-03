package com.rishi.seatreservation.reservation;

import com.rishi.seatreservation.common.Ids;
import com.rishi.seatreservation.security.Auth;
import com.rishi.seatreservation.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    /** USER or ADMIN. 201 for a new reservation, 200 (Idempotent-Replayed: true) for a repeated key. */
    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") String showId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody(required = false) ReserveRequest body,
            HttpServletRequest request) {
        AuthUser user = Auth.requireUser(request);
        ReserveResult result = reservationService.reserve(
                Ids.parse(showId, "show id"), user.getUserId(), body, idempotencyKey);
        if (result.isReplayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.getReservation());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.getReservation());
    }

    /** USER or ADMIN, owner only: another user's reservation is a 404. */
    @PostMapping("/reservations/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") String reservationId, HttpServletRequest request) {
        AuthUser user = Auth.requireUser(request);
        return reservationService.cancel(Ids.parse(reservationId, "reservation id"), user.getUserId());
    }
}
