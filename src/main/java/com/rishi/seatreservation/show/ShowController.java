package com.rishi.seatreservation.show;

import com.rishi.seatreservation.common.Ids;
import com.rishi.seatreservation.security.Auth;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;

    public ShowController(ShowService showService) {
        this.showService = showService;
    }

    /** ADMIN only. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowResponse create(@RequestBody(required = false) CreateShowRequest body, HttpServletRequest request) {
        Auth.requireAdmin(request);
        return showService.create(body);
    }

    /** Public: no token needed. */
    @GetMapping("/{id}")
    public ShowResponse get(@PathVariable("id") String id) {
        return showService.get(Ids.parse(id, "show id"));
    }

    /** ADMIN only. */
    @PostMapping("/{id}/cancel")
    public CancelShowResponse cancel(@PathVariable("id") String id, HttpServletRequest request) {
        Auth.requireAdmin(request);
        return showService.cancel(Ids.parse(id, "show id"));
    }
}
