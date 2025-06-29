package com.example.demo.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import com.example.demo.DTO.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import com.example.demo.Model.Booking;
import com.example.demo.Model.CarRentalUser;
import com.example.demo.Model.ScheduledDate;
import com.example.demo.Model.SchedulingBooking;
import com.example.demo.Model.Vendor;
import com.example.demo.Model.VendorDriver;
import com.example.demo.Repository.ScheduleBookingRepository;
import com.example.demo.Repository.ScheduleDates;

import jakarta.transaction.Transactional;

@Service
public class ScheduleBookingService {

    @Autowired
    private ScheduleBookingRepository scheduleBookingRepository;

    @Autowired
    private ScheduleDates scheduleDates;

    @Autowired
    private RestTemplate restTemplate;

    private static final Logger logger = LoggerFactory.getLogger(ScheduleBookingService.class);
    private final String apiKey = "AIzaSyCelDo4I5cPQ72TfCTQW-arhPZ7ALNcp8w";

    @Transactional
    public MultiDateAssignmentResponseDTO assignDriverBookingWithResponse(int bookingId, int vendorDriverId) {
        MultiDateAssignmentResponseDTO multiResponse = new MultiDateAssignmentResponseDTO();

        try {
            SchedulingBooking newBooking = scheduleBookingRepository.findById(bookingId)
                    .orElse(null);

            if (newBooking == null) {
                DriverAssignmentResponseDTO errorResponse = DriverAssignmentResponseDTO.bookingNotFound(bookingId);
                multiResponse.addDateResult(LocalDate.now(), errorResponse);
                return multiResponse;
            }

            List<LocalDate> bookingDates = newBooking.getScheduledDates().stream()
                    .map(ScheduledDate::getDate)
                    .collect(Collectors.toList());

            for (LocalDate bookingDate : bookingDates) {
                logger.info("Assigning driver {} to booking {} for date {}", vendorDriverId, bookingId, bookingDate);

                try {
                    SchedulingBooking result = assignDriverForSpecificDate(newBooking, vendorDriverId, bookingDate);

                    DriverAssignmentResponseDTO successResponse = DriverAssignmentResponseDTO.success(result,
                            "Driver successfully assigned for " + bookingDate);
                    multiResponse.addDateResult(bookingDate, successResponse);

                } catch (TimeGapViolationException e) {
                    String message = e.getMessage();
                    String nextTime = extractNextAvailableTime(message);
                    DriverAssignmentResponseDTO errorResponse = DriverAssignmentResponseDTO.timeGapViolation(nextTime, bookingDate);
                    multiResponse.addDateResult(bookingDate, errorResponse);

                } catch (SlotCapacityExceededException e) {
                    DriverAssignmentResponseDTO errorResponse = DriverAssignmentResponseDTO.capacityExceeded(
                            e.getSlotId(), e.getCapacity(), bookingDate);
                    multiResponse.addDateResult(bookingDate, errorResponse);

                } catch (RuntimeException e) {
                    String message = e.getMessage();
                    DriverAssignmentResponseDTO errorResponse;

                    if (message.contains("route overlaps with existing booking")) {
                        String existingRoute = extractExistingRoute(message);
                        List<String> suggestions = Arrays.asList(
                                "Try a different pickup time",
                                "Choose a pickup location closer to the existing route",
                                "Select a different driver for this route"
                        );
                        errorResponse = DriverAssignmentResponseDTO.routeOverlap(existingRoute, bookingDate, suggestions);

                    } else if (message.contains("cab type mismatch")) {
                        String[] cabTypes = extractCabTypes(message);
                        errorResponse = DriverAssignmentResponseDTO.cabTypeMismatch(cabTypes[0], cabTypes[1]);

                    } else if (message.contains("Vendor driver not found")) {
                        errorResponse = DriverAssignmentResponseDTO.driverNotFound(vendorDriverId);

                    } else if (message.contains("Invalid time format")) {
                        errorResponse = DriverAssignmentResponseDTO.invalidTime(newBooking.getTime());

                    } else if (message.contains("CabType is required")) {
                        errorResponse = DriverAssignmentResponseDTO.missingCabType();

                    } else {
                        errorResponse = new DriverAssignmentResponseDTO(false, message, "ASSIGNMENT_ERROR");
                    }

                    multiResponse.addDateResult(bookingDate, errorResponse);
                }
            }

            return multiResponse;

        } catch (Exception e) {
            logger.error("Unexpected error during driver assignment: {}", e.getMessage());
            DriverAssignmentResponseDTO errorResponse = new DriverAssignmentResponseDTO(
                    false,
                    "An unexpected error occurred: " + e.getMessage(),
                    "SYSTEM_ERROR"
            );
            multiResponse.addDateResult(LocalDate.now(), errorResponse);
            return multiResponse;
        }
    }

    private String extractNextAvailableTime(String message) {
        try {
            int index = message.indexOf("Next available time: ");
            if (index != -1) {
                return message.substring(index + 21).trim();
            }
        } catch (Exception e) {
            logger.debug("Could not extract next available time from message: {}", message);
        }
        return "Please try a different time";
    }

    private String extractExistingRoute(String message) {
        try {
            int start = message.indexOf("(") + 1;
            int end = message.indexOf(")", start);
            if (start > 0 && end > start) {
                return message.substring(start, end);
            }
        } catch (Exception e) {
            logger.debug("Could not extract existing route from message: {}", message);
        }
        return "existing route";
    }

    private String[] extractCabTypes(String message) {
        try {
            String[] result = new String[2];
            if (message.contains("Slot requires") && message.contains("but booking has")) {
                int requiresStart = message.indexOf("Slot requires ") + 14;
                int requiresEnd = message.indexOf(",", requiresStart);
                int hasStart = message.indexOf("but booking has ") + 16;

                result[0] = message.substring(requiresStart, requiresEnd).trim();
                result[1] = message.substring(hasStart).trim();
                return result;
            }
        } catch (Exception e) {
            logger.debug("Could not extract cab types from message: {}", message);
        }
        return new String[]{"required cab type", "requested cab type"};
    }

    @Transactional
    private SchedulingBooking assignDriverForSpecificDate(SchedulingBooking newBooking, int vendorDriverId, LocalDate bookingDate) {
        logger.info("Processing driver assignment for booking {} on date {}", newBooking.getId(), bookingDate);

        if (newBooking.getCabType() == null) {
            throw new RuntimeException("CabType is required for booking assignment.");
        }

        // Verify driver exists
        String driverUrl = "https://api.worldtriplink.com/vendorDriver/" + vendorDriverId;
        try {
            restTemplate.getForObject(driverUrl, VendorDriver.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RuntimeException("Vendor driver not found with ID: " + vendorDriverId);
        }

        LocalTime newBookingTime;
        try {
            newBookingTime = LocalTime.parse(newBooking.getTime(), DateTimeFormatter.ofPattern("HH:mm"));
        } catch (Exception e) {
            throw new RuntimeException("Invalid time format in booking. Expected HH:mm format.");
        }

        logger.info("New booking time: {} for date: {}", newBookingTime, bookingDate);

        // Get existing bookings for this driver and date
        List<SchedulingBooking> existingBookings = getBookingsByDriverAndDate(vendorDriverId, bookingDate);

        // Filter out current booking
        List<SchedulingBooking> filteredBookings = existingBookings.stream()
                .filter(booking -> booking.getId() != newBooking.getId())
                .collect(Collectors.toList());

        logger.info("DEBUG: After filtering current booking {} - Found {} other existing bookings",
                newBooking.getId(), filteredBookings.size());

        if (filteredBookings.isEmpty()) {
            logger.info("No existing bookings for driver {} on date {}. Creating new slot.", vendorDriverId, bookingDate);
            return createNewSlotAndAssign(newBooking, vendorDriverId, bookingDate, newBookingTime);
        }

        logger.info("Found {} existing bookings for driver {} on date {}", filteredBookings.size(), vendorDriverId, bookingDate);

        // Group existing bookings by their slot IDs for this specific date
        Map<String, List<SchedulingBooking>> slotGroups = groupBookingsBySlotForDate(filteredBookings, bookingDate);

        logger.info("Found {} different slots for driver {} on date {}", slotGroups.size(), vendorDriverId, bookingDate);

        String activeSlotId = null;
        List<SchedulingBooking> activeSlotBookings = null;

        for (Map.Entry<String, List<SchedulingBooking>> slotEntry : slotGroups.entrySet()) {
            String slotId = slotEntry.getKey();
            List<SchedulingBooking> slotBookings = slotEntry.getValue();

            LocalTime slotEnd = getSlotEndTime(slotBookings);

            logger.info("Checking slot {} on date {} - ends at {}, new booking time: {}",
                    slotId, bookingDate, slotEnd, newBookingTime);

            // Check if new booking time is AFTER slot end time (slot has expired for this booking)
            if (newBookingTime.isAfter(slotEnd)) {
                logger.info("Slot {} has expired for booking time {} on date {} (slot ended at {})",
                        slotId, newBookingTime, bookingDate, slotEnd);
                continue;
            } else {
                logger.info("Found active slot: {} on date {} (booking time {} is before/at slot end {})",
                        slotId, bookingDate, newBookingTime, slotEnd);
                activeSlotId = slotId;
                activeSlotBookings = slotBookings;
                break;
            }
        }

        if (activeSlotId == null) {
            logger.info("No active slot found for driver {} on date {} and booking time {}. Creating new slot.",
                    vendorDriverId, bookingDate, newBookingTime);
            return createNewSlotAndAssign(newBooking, vendorDriverId, bookingDate, newBookingTime);
        }

        logger.info("Found active slot {} with {} bookings for driver {} on date {}",
                activeSlotId, activeSlotBookings.size(), vendorDriverId, bookingDate);

        LocalTime slotStart = getSlotStartTime(activeSlotBookings);
        LocalTime slotEnd = getSlotEndTime(activeSlotBookings);

        logger.info("Active slot details for date {} - Start: {}, End: {}, New booking time: {}",
                bookingDate, slotStart, slotEnd, newBookingTime);

        // Check 20-minute gap BEFORE capacity check
        LocalTime minimumAllowedTime = slotStart.plusMinutes(20);
        logger.info("20-minute gap check for date {} - Slot start: {}, Minimum allowed: {}, New booking: {}",
                bookingDate, slotStart, minimumAllowedTime, newBookingTime);

        // Allow booking at slot start time or after the 20-minute gap
        if (newBookingTime.isAfter(slotStart) && newBookingTime.isBefore(minimumAllowedTime)) {
            throw new TimeGapViolationException(
                    "Booking time " + newBookingTime + " is within 20-minute gap from slot start " +
                            slotStart + " on date " + bookingDate + ". Next available time: " + minimumAllowedTime);
        }

        // 20-minute gap check passed - try to add to existing slot
        logger.info("20-minute gap check passed for date {}. Attempting to add to existing slot {}.", bookingDate, activeSlotId);
        return assignToExistingSlotForDate(newBooking, vendorDriverId, bookingDate, newBookingTime,
                activeSlotId, activeSlotBookings, slotStart);
    }

    // NEW METHOD: Group bookings by slot ID for a specific date
    private Map<String, List<SchedulingBooking>> groupBookingsBySlotForDate(List<SchedulingBooking> bookings, LocalDate date) {
        Map<String, List<SchedulingBooking>> slotGroups = new HashMap<>();

        for (SchedulingBooking booking : bookings) {
            // Find the scheduled date entry for this specific date
            ScheduledDate scheduledDate = booking.getScheduledDates().stream()
                    .filter(sd -> sd.getDate().equals(date))
                    .findFirst()
                    .orElse(null);

            if (scheduledDate != null && scheduledDate.getSlotId() != null) {
                String slotId = scheduledDate.getSlotId();
                slotGroups.computeIfAbsent(slotId, k -> new ArrayList<>()).add(booking);
            }
        }

        return slotGroups;
    }

    private List<SchedulingBooking> getBookingsByDriverAndDate(int vendorDriverId, LocalDate date) {
        return scheduleBookingRepository.findByVendorDriverIdAndScheduledDatesDate(vendorDriverId, date);
    }

    private SchedulingBooking createNewSlotAndAssign(SchedulingBooking newBooking, int vendorDriverId,
                                                     LocalDate bookingDate, LocalTime bookingTime) {
        logger.info("Creating new slot for driver {} with booking time {} on date {}", vendorDriverId, bookingTime, bookingDate);

        String newSlotId = generateNewSlotId(vendorDriverId, bookingDate, bookingTime);

        ScheduledDate targetScheduledDate = newBooking.getScheduledDates().stream()
                .filter(sd -> sd.getDate().equals(bookingDate))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("ScheduledDate not found for booking " + newBooking.getId() + " and date " + bookingDate));

        targetScheduledDate.setSlotId(newSlotId);

        newBooking.setVendorDriverId(vendorDriverId);

        logger.info("Created new slot {} for date {} on scheduled date ID {}", newSlotId, bookingDate, targetScheduledDate.getId());

        // Save the booking (this will cascade to scheduled dates)
        return scheduleBookingRepository.save(newBooking);
    }

    private String generateNewSlotId(int vendorDriverId, LocalDate bookingDate, LocalTime bookingTime) {
        return "SLOT_" + vendorDriverId + "_" +
                bookingDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "_" +
                bookingTime.format(DateTimeFormatter.ofPattern("HHmm")) + "_" +
                System.currentTimeMillis();
    }

    // UPDATED METHOD: Assign to existing slot for specific date
    private SchedulingBooking assignToExistingSlotForDate(SchedulingBooking newBooking, int vendorDriverId,
                                                          LocalDate bookingDate, LocalTime bookingTime,
                                                          String slotId, List<SchedulingBooking> slotBookings,
                                                          LocalTime slotStart) {

        // Check cab type compatibility
        if (!slotBookings.isEmpty()) {
            String existingCabType = slotBookings.get(0).getCabType();
            String requestingCabType = newBooking.getCabType();

            if (existingCabType == null || requestingCabType == null) {
                logger.error("Null cabType found during comparison");
                throw new RuntimeException("CabType cannot be null for slot assignment");
            }

            if (!existingCabType.equalsIgnoreCase(requestingCabType)) {
                logger.debug("Cab type mismatch - existing: {}, requesting: {}", existingCabType, requestingCabType);
                throw new RuntimeException("Cannot assign: cab type mismatch. Slot requires " +
                        existingCabType + ", but booking has " + requestingCabType);
            }
        }

        int availableSeats = getAvailableSeatsForCabType(newBooking.getCabType());
        if (availableSeats == -1) {
            throw new RuntimeException("Unknown cab type: " + newBooking.getCabType());
        }

        int usedSeats = slotBookings.size();
        int remainingSeats = availableSeats - usedSeats;

        logger.debug("Capacity check for date {} - Available seats: {}, Used seats: {}, Remaining seats: {}",
                bookingDate, availableSeats, usedSeats, remainingSeats);

        if (remainingSeats < 1) {
            throw new SlotCapacityExceededException(
                    "Cannot assign: slot " + slotId + " is at capacity on date " + bookingDate + ". " +
                            "Available: " + availableSeats + " seats, " +
                            "Used: " + usedSeats + " seats, " +
                            "Remaining: " + remainingSeats + " seats",
                    slotId, availableSeats);
        }

        // Check route overlap with existing bookings
        for (SchedulingBooking existingBooking : slotBookings) {
            if (isRouteOverlapping(
                    existingBooking.getPickUpLocation(),
                    existingBooking.getDropLocation(),
                    newBooking.getPickUpLocation(),
                    newBooking.getDropLocation())) {
                throw new RuntimeException(
                        "Cannot assign driver - route overlaps with existing booking ID: " +
                                existingBooking.getId() + " (" + existingBooking.getPickUpLocation() +
                                " to " + existingBooking.getDropLocation() + ") on date " + bookingDate);
            }
        }

        // Find the specific scheduled date and assign slot ID to it
        ScheduledDate targetScheduledDate = newBooking.getScheduledDates().stream()
                .filter(sd -> sd.getDate().equals(bookingDate))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("ScheduledDate not found for booking " + newBooking.getId() + " and date " + bookingDate));

        // Set slot ID on the specific scheduled date (NOT on the booking)
        targetScheduledDate.setSlotId(slotId);

        // Set vendor driver ID on booking
        newBooking.setVendorDriverId(vendorDriverId);

        logger.info("Successfully assigned booking {} to slot {} for driver {} on date {}. " +
                        "Slot now has {}/{} seats used",
                newBooking.getId(), slotId, vendorDriverId, bookingDate,
                usedSeats + 1, availableSeats);

        return scheduleBookingRepository.save(newBooking);
    }

    public boolean isRouteOverlapping(String existingPickup, String existingDrop, String newPickup, String newDrop) {
        try {
            logger.info("Checking route overlap between [{}→{}] and [{}→{}]",
                    existingPickup, existingDrop, newPickup, newDrop);

            // Rule 1: New pickup must be along existing route (no backtracking)
            if (!isPickupAlongRoute(existingPickup, existingDrop, newPickup)) {
                logger.info("Route overlap detected: new pickup {} requires backtracking from route {}→{}",
                        newPickup, existingPickup, existingDrop);
                return true; // Overlap = reject
            }

            // Rule 2: New drop must be in same general direction
            if (!isSameGeneralDirection(existingPickup, existingDrop, newPickup, newDrop)) {
                logger.info("Route overlap detected: routes go in different directions");
                return true; // Overlap = reject
            }

            // Rule 3: Overall route should be reasonably efficient
            if (!isOverallRouteEfficient(existingPickup, existingDrop, newPickup, newDrop)) {
                logger.info("Route overlap detected: combined route is inefficient");
                return true; // Overlap = reject
            }

            logger.info("No route overlap detected - routes can be efficiently combined");
            return false; // No overlap = accept

        } catch (Exception e) {
            logger.error("Error checking route overlap: " + e.getMessage());
            // In case of error, be conservative and assume overlap
            return true;
        }
    }

    private boolean isPickupAlongRoute(String existingPickup, String existingDrop, String newPickup) {
        try {
            // Get distances
            double existingRouteDistance = getDirectRouteDistance(existingPickup, existingDrop);
            double pickupToStart = getDirectRouteDistance(existingPickup, newPickup);
            double pickupToEnd = getDirectRouteDistance(newPickup, existingDrop);

            // New pickup should be roughly between start and end
            // Allow 30% tolerance for slight detours
            double tolerance = 1.3;
            boolean isAlong = (pickupToStart + pickupToEnd) <= (existingRouteDistance * tolerance);

            logger.debug("Pickup along route check - Existing: {:.2f}km, Via pickup: {:.2f}km, Along route: {}",
                    existingRouteDistance/1000, (pickupToStart + pickupToEnd)/1000, isAlong);

            return isAlong;

        } catch (Exception e) {
            logger.error("Error checking pickup along route: " + e.getMessage());
            return false;
        }
    }

    private boolean isSameGeneralDirection(String existingPickup, String existingDrop, String newPickup, String newDrop) {
        try {
            // Get coordinates
            double[] coordsExistingPickup = getCoordinates(existingPickup);
            double[] coordsExistingDrop = getCoordinates(existingDrop);
            double[] coordsNewPickup = getCoordinates(newPickup);
            double[] coordsNewDrop = getCoordinates(newDrop);

            if (coordsExistingPickup == null || coordsExistingDrop == null ||
                    coordsNewPickup == null || coordsNewDrop == null) {
                return false;
            }

            // Calculate direction vectors
            double existingDirectionLat = coordsExistingDrop[0] - coordsExistingPickup[0];
            double existingDirectionLng = coordsExistingDrop[1] - coordsExistingPickup[1];
            double newDirectionLat = coordsNewDrop[0] - coordsNewPickup[0];
            double newDirectionLng = coordsNewDrop[1] - coordsNewPickup[1];

            // Calculate angle between direction vectors
            double dotProduct = existingDirectionLat * newDirectionLat + existingDirectionLng * newDirectionLng;
            double existingMagnitude = Math.sqrt(existingDirectionLat * existingDirectionLat + existingDirectionLng * existingDirectionLng);
            double newMagnitude = Math.sqrt(newDirectionLat * newDirectionLat + newDirectionLng * newDirectionLng);

            if (existingMagnitude == 0 || newMagnitude == 0) {
                return false;
            }

            double cosAngle = dotProduct / (existingMagnitude * newMagnitude);
            double angle = Math.acos(Math.max(-1.0, Math.min(1.0, cosAngle)));
            double angleDegrees = Math.toDegrees(angle);

            // Allow up to 60 degrees difference for same general direction
            boolean sameDirection = angleDegrees <= 60.0;

            logger.debug("Direction check - Angle: {:.1f}°, Same direction: {}", angleDegrees, sameDirection);

            return sameDirection;

        } catch (Exception e) {
            logger.error("Error checking same direction: " + e.getMessage());
            return false;
        }
    }

    private boolean isOverallRouteEfficient(String existingPickup, String existingDrop, String newPickup, String newDrop) {
        try {
            // Calculate separate route distances
            double existingRouteDistance = getDirectRouteDistance(existingPickup, existingDrop);
            double newRouteDistance = getDirectRouteDistance(newPickup, newDrop);
            double separateTotal = existingRouteDistance + newRouteDistance;

            // Calculate optimal combined route distance
            // Try different sequences to find most efficient
            double[] sequences = {
                    getCombinedRouteDistance(existingPickup, newPickup, existingDrop, newDrop),
                    getCombinedRouteDistance(existingPickup, newPickup, newDrop, existingDrop),
                    getCombinedRouteDistance(newPickup, existingPickup, existingDrop, newDrop),
                    getCombinedRouteDistance(newPickup, existingPickup, newDrop, existingDrop)
            };

            double bestCombinedDistance = Double.MAX_VALUE;
            for (double distance : sequences) {
                if (distance > 0 && distance < bestCombinedDistance) {
                    bestCombinedDistance = distance;
                }
            }

            if (bestCombinedDistance == Double.MAX_VALUE) {
                return false;
            }

            // Check efficiency - combined route should not be more than 50% longer than separate routes
            double efficiency = bestCombinedDistance / separateTotal;
            boolean isEfficient = efficiency <= 1.5;

            logger.debug("Efficiency check - Separate: {:.2f}km, Combined: {:.2f}km, Efficiency: {:.1f}%, Efficient: {}",
                    separateTotal/1000, bestCombinedDistance/1000, efficiency * 100, isEfficient);

            return isEfficient;

        } catch (Exception e) {
            logger.error("Error checking route efficiency: " + e.getMessage());
            return false;
        }
    }

    private double getCombinedRouteDistance(String point1, String point2, String point3, String point4) {
        try {
            double dist1to2 = getDirectRouteDistance(point1, point2);
            double dist2to3 = getDirectRouteDistance(point2, point3);
            double dist3to4 = getDirectRouteDistance(point3, point4);

            return dist1to2 + dist2to3 + dist3to4;

        } catch (Exception e) {
            logger.error("Error calculating combined route distance: " + e.getMessage());
            return -1;
        }
    }

    private double[] getCoordinates(String location) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://maps.googleapis.com/maps/api/geocode/json")
                    .queryParam("address", UriUtils.encode(location, "UTF-8"))
                    .queryParam("key", apiKey)
                    .build()
                    .toString();

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && "OK".equals(response.get("status"))) {
                List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
                if (results != null && !results.isEmpty()) {
                    Map<String, Object> geometry = (Map<String, Object>) results.get(0).get("geometry");
                    Map<String, Object> locationMap = (Map<String, Object>) geometry.get("location");

                    double lat = ((Number) locationMap.get("lat")).doubleValue();
                    double lng = ((Number) locationMap.get("lng")).doubleValue();

                    return new double[]{lat, lng};
                }
            }

            return null;

        } catch (Exception e) {
            logger.error("Error getting coordinates for location: " + location + " - " + e.getMessage());
            return null;
        }
    }

    private double getDirectRouteDistance(String origin, String destination) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl("https://maps.googleapis.com/maps/api/directions/json")
                    .queryParam("origin", UriUtils.encode(origin, "UTF-8"))
                    .queryParam("destination", UriUtils.encode(destination, "UTF-8"))
                    .queryParam("key", apiKey)
                    .build()
                    .toString();

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && "OK".equals(response.get("status"))) {
                List<Map<String, Object>> routes = (List<Map<String, Object>>) response.get("routes");
                if (routes != null && !routes.isEmpty()) {
                    Map<String, Object> route = routes.get(0);
                    List<Map<String, Object>> legs = (List<Map<String, Object>>) route.get("legs");

                    if (legs != null && !legs.isEmpty()) {
                        Map<String, Object> distance = (Map<String, Object>) legs.get(0).get("distance");
                        if (distance != null) {
                            return ((Number) distance.get("value")).doubleValue();
                        }
                    }
                }
            }

            return 0;

        } catch (Exception e) {
            logger.error("Error getting direct route distance: " + e.getMessage());
            return 0;
        }
    }

    private int getAvailableSeatsForCabType(String cabType) {
        if (cabType == null) return -1;

        switch (cabType.toLowerCase()) {
            case "suv":
                return 4;
            case "sedan":
            case "sedan premium":
            case "hatchback":
                return 3;
            default:
                return -1;
        }
    }

    private LocalTime getSlotStartTime(List<SchedulingBooking> slotBookings) {
        return slotBookings.stream()
                .map(booking -> LocalTime.parse(booking.getTime(), DateTimeFormatter.ofPattern("HH:mm")))
                .min(LocalTime::compareTo)
                .orElse(LocalTime.now());
    }

    private LocalTime getSlotEndTime(List<SchedulingBooking> slotBookings) {
        LocalTime latestPickup = slotBookings.stream()
                .map(booking -> LocalTime.parse(booking.getTime(), DateTimeFormatter.ofPattern("HH:mm")))
                .max(LocalTime::compareTo)
                .orElse(LocalTime.now());

        return latestPickup.plusHours(1).plusMinutes(30);
    }

    // UPDATED METHOD: Get driver slots with new logic
    public DriverSlotsResponseDTO getDriverSlots(int vendorDriverId) {
        try {
            String driverUrl = "https://api.worldtriplink.com/vendorDriver/" + vendorDriverId;
            try {
                restTemplate.getForObject(driverUrl, VendorDriver.class);
            } catch (HttpClientErrorException.NotFound e) {
                throw new RuntimeException("Vendor driver not found with ID: " + vendorDriverId);
            }

            List<SchedulingBooking> allBookings = scheduleBookingRepository.findByVendorDriverId(vendorDriverId);

            if (allBookings.isEmpty()) {
                return new DriverSlotsResponseDTO(new ArrayList<>());
            }

            // Group bookings by slot and date using ScheduledDate.slotId
            Map<String, List<BookingDatePair>> groupedBookings = groupBookingsBySlotAndDateNew(allBookings);

            // Convert to DTOs and sort
            List<SlotDTO> slots = convertToSlotDTOs(groupedBookings);

            // Sort by latest date first
            slots.sort((slot1, slot2) -> slot2.getDate().compareTo(slot1.getDate()));

            return new DriverSlotsResponseDTO(slots);

        } catch (Exception e) {
            logger.error("Error fetching driver slots for vendorDriverId {}: {}", vendorDriverId, e.getMessage());
            throw new RuntimeException("Error fetching driver slots: " + e.getMessage());
        }
    }

    // UPDATED METHOD: Group bookings using ScheduledDate.slotId
    private Map<String, List<BookingDatePair>> groupBookingsBySlotAndDateNew(List<SchedulingBooking> bookings) {
        Map<String, List<BookingDatePair>> grouped = new HashMap<>();

        for (SchedulingBooking booking : bookings) {
            for (ScheduledDate scheduledDate : booking.getScheduledDates()) {
                // Use slotId from ScheduledDate, not from SchedulingBooking
                String slotId = scheduledDate.getSlotId();
                String key = createGroupingKey(slotId, scheduledDate.getDate());
                BookingDatePair pair = new BookingDatePair(booking, scheduledDate);
                grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(pair);
            }
        }

        return grouped;
    }

    // Helper class to pair booking with specific date
    private static class BookingDatePair {
        private final SchedulingBooking booking;
        private final ScheduledDate scheduledDate;

        public BookingDatePair(SchedulingBooking booking, ScheduledDate scheduledDate) {
            this.booking = booking;
            this.scheduledDate = scheduledDate;
        }

        public SchedulingBooking getBooking() { return booking; }
        public ScheduledDate getScheduledDate() { return scheduledDate; }
    }

    private String createGroupingKey(String slotId, LocalDate date) {
        String slot = (slotId != null) ? slotId : "NULL_SLOT";
        return slot + "_" + date.toString();
    }

    private List<SlotDTO> convertToSlotDTOs(Map<String, List<BookingDatePair>> groupedBookings) {
        List<SlotDTO> slots = new ArrayList<>();

        for (Map.Entry<String, List<BookingDatePair>> entry : groupedBookings.entrySet()) {
            String key = entry.getKey();
            List<BookingDatePair> bookingPairs = entry.getValue();

            // Parse key to get slotId and date
            String[] parts = key.split("_(?=\\d{4}-\\d{2}-\\d{2}$)"); // Split at date pattern
            String slotId = parts[0].equals("NULL_SLOT") ? null : parts[0];
            LocalDate date = LocalDate.parse(parts[1]);

            // Convert bookings to DTOs
            List<SlotBookingDTO> bookingDTOs = bookingPairs.stream()
                    .map(pair -> convertToSlotBookingDTO(pair.getBooking(), pair.getScheduledDate()))
                    .sorted((b1, b2) -> b1.getPickupTime().compareTo(b2.getPickupTime())) // Sort by pickup time
                    .collect(Collectors.toList());

            SlotDTO slotDTO = new SlotDTO(slotId, date, bookingDTOs.size(), bookingDTOs);
            slots.add(slotDTO);
        }

        return slots;
    }

    // UPDATED METHOD: Convert to slot booking DTO
    private SlotBookingDTO convertToSlotBookingDTO(SchedulingBooking booking, ScheduledDate scheduledDate) {
        String status = (scheduledDate.getStatus() != null) ? scheduledDate.getStatus() : "PENDING";

        // Get userId from carRentalUserId field (primitive int)
        int userId = booking.getCarRentalUserId();

        // Get vendorId from vendorId field (Long), convert to int
        int vendorId = 0;
        if (booking.getVendorId() != null) {
            vendorId = booking.getVendorId().intValue();
        }

        // Get vendorDriverId (primitive int)
        int vendorDriverId = booking.getVendorDriverId();

        return new SlotBookingDTO(
                userId,
                vendorId,
                vendorDriverId,
                booking.getId(),
                booking.getTime(),
                booking.getPickUpLocation(),
                booking.getDropLocation(),
                status
        );
    }

    public static class SlotCapacityExceededException extends RuntimeException {
        private final String slotId;
        private final int capacity;

        public SlotCapacityExceededException(String message, String slotId, int capacity) {
            super(message);
            this.slotId = slotId;
            this.capacity = capacity;
        }

        public String getSlotId() { return slotId; }
        public int getCapacity() { return capacity; }
    }

    public static class TimeGapViolationException extends RuntimeException {
        public TimeGapViolationException(String message) {
            super(message);
        }
    }

    public enum DriverState {
        AVAILABLE,      // No active slot or slot expired
        SLOT_ACTIVE,    // Has active slot with available capacity
        SLOT_EXPIRED    // Slot has expired, ready for new slot
    }

    public static class DriverSlotStatus {
        private final DriverState state;
        private final String slotId;
        private final LocalTime slotStart;
        private final LocalTime slotEnd;
        private final LocalDate slotDate;

        public DriverSlotStatus(DriverState state, String slotId, LocalTime slotStart, LocalTime slotEnd, LocalDate slotDate) {
            this.state = state;
            this.slotId = slotId;
            this.slotStart = slotStart;
            this.slotEnd = slotEnd;
            this.slotDate = slotDate;
        }

        public DriverState getState() { return state; }
        public String getSlotId() { return slotId; }
        public LocalTime getSlotStart() { return slotStart; }
        public LocalTime getSlotEnd() { return slotEnd; }
        public LocalDate getSlotDate() { return slotDate; }
    }

    @Transactional
    public SchedulingBooking createSchedule(
            int userId,
            String pickUpLocation,
            String dropLocation,
            String time,
            String returnTime,
            String shiftTime,
            List<LocalDate> dates
    ) {
        String userServiceUrl = "https://api.worldtriplink.com/auth/getCarRentalUserById/" + userId;
        CarRentalUser user = restTemplate.getForObject(userServiceUrl, CarRentalUser.class);
        if (user == null) {
            throw new IllegalArgumentException("User not found");
        }

        SchedulingBooking booking = new SchedulingBooking();
        booking.setPickUpLocation(pickUpLocation);
        booking.setDropLocation(dropLocation);
        booking.setTime(time);
        booking.setReturnTime(returnTime);
        booking.setShiftTime(shiftTime);
        booking.setUser(user);

        for (LocalDate date : dates) {
            ScheduledDate sd = new ScheduledDate();
            sd.setDate(date);
            sd.setSchedulingBooking(booking);
            booking.getScheduledDates().add(sd);
        }

        return scheduleBookingRepository.save(booking);
    }

    public SchedulingBooking assignVendorToBooking(int bookingId, Long vendorId) {
        SchedulingBooking booking = scheduleBookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found with ID: " + bookingId));

        String vendorUrl = "https://api.worldtriplink.com/vendors/" + vendorId;
        try {
            restTemplate.getForObject(vendorUrl, Vendor.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new RuntimeException("Vendor not found with ID: " + vendorId);
        }

        booking.setVendorId(vendorId);
        return scheduleBookingRepository.save(booking);
    }

    public SchedulingBookingDTO getBookingWithVendorDTO(int bookingId) {
        SchedulingBooking booking = scheduleBookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("Booking not found"));

        String vendorServiceUrl = "https://api.worldtriplink.com/vendors/" + booking.getVendorId();
        String vendorDriverServiceUrl = "https://api.worldtriplink.com/vendorDriver/" + booking.getVendorDriverId();
        String userServiceUrl = "https://api.worldtriplink.com/auth/getCarRentalUserById/" + booking.getUser().getId();

        Vendor vendor = restTemplate.getForObject(vendorServiceUrl, Vendor.class);
        VendorDriver vendorDriver = restTemplate.getForObject(vendorDriverServiceUrl, VendorDriver.class);
        CarRentalUser user = restTemplate.getForObject(userServiceUrl, CarRentalUser.class);

        booking.setVendor(vendor);
        booking.setVendorDriver(vendorDriver);

        SchedulingBookingDTO dto = new SchedulingBookingDTO();
        dto.setId(booking.getId());
        dto.setPickUpLocation(booking.getPickUpLocation());
        dto.setDropLocation(booking.getDropLocation());
        dto.setTime(booking.getTime());
        dto.setReturnTime(booking.getReturnTime());
        dto.setShiftTime(booking.getShiftTime());
        dto.setBookingType(booking.getBookingType());
        dto.setDateOfList(booking.getDateOfList());

        VendorDTO vendorDTO = new VendorDTO();
        vendorDTO.setId(vendor.getId());
        vendorDTO.setVendorCompanyName(vendor.getVendorCompanyName());
        vendorDTO.setContactNo(vendor.getContactNo());
        vendorDTO.setAlternateMobileNo(vendor.getAlternateMobileNo());
        vendorDTO.setCity(vendor.getCity());
        vendorDTO.setVendorEmail(vendor.getVendorEmail());
        dto.setVendor(vendorDTO);

        VendorDriverDTO driverDTO = new VendorDriverDTO();
        driverDTO.setVendorDriverId(vendorDriver.getVendorDriverId());
        driverDTO.setDriverName(vendorDriver.getDriverName());
        driverDTO.setContactNo(vendorDriver.getContactNo());
        driverDTO.setAltContactNo(vendorDriver.getAltContactNo());
        dto.setVendorDriver(driverDTO);

        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setUserName(user.getUserName());
        userDTO.setLastName(user.getLastName());
        userDTO.setEmail(user.getEmail());
        userDTO.setGender(user.getGender());
        userDTO.setPhone(user.getPhone());
        dto.setUser(userDTO);

        List<ScheduleDateBookingDTO> scheduledDateDTOs = booking.getScheduledDates().stream().map(sd -> {
            ScheduleDateBookingDTO sdDTO = new ScheduleDateBookingDTO();
            sdDTO.setId(sd.getId());
            sdDTO.setDate(sd.getDate());
            return sdDTO;
        }).toList();
        dto.setScheduledDates(scheduledDateDTOs);

        return dto;
    }

    public List<SchedulingBooking> getBookingByUserId(int userId){
        return this.scheduleBookingRepository.findByCarRentalUserId(userId);
    }

    public SchedulingBookingDTO getByScheduleBookingId(int id){
        SchedulingBooking schedulingBooking = this.scheduleBookingRepository.findById(id).get();
        SchedulingBookingDTO dtoS = new SchedulingBookingDTO();
        dtoS.setId(schedulingBooking.getId());
        dtoS.setBookingId(schedulingBooking.getBookId());
        dtoS.setPickUpLocation(schedulingBooking.getPickUpLocation());
        dtoS.setDropLocation(schedulingBooking.getDropLocation());
        dtoS.setTime(schedulingBooking.getTime());
        dtoS.setReturnTime(schedulingBooking.getReturnTime());
        dtoS.setShiftTime(schedulingBooking.getShiftTime());
        dtoS.setBookingType(schedulingBooking.getBookingType());
        try {
            String vendorServiceUrl = "https://api.worldtriplink.com/vendors/" + schedulingBooking.getVendorId();
            Vendor vendor = restTemplate.getForObject(vendorServiceUrl, Vendor.class);
            if (vendor != null) {
                VendorDTO vendorDTO = new VendorDTO();
                vendorDTO.setId(vendor.getId());
                vendorDTO.setVendorCompanyName(vendor.getVendorCompanyName());
                vendorDTO.setContactNo(vendor.getContactNo());
                vendorDTO.setAlternateMobileNo(vendor.getAlternateMobileNo());
                vendorDTO.setCity(vendor.getCity());
                vendorDTO.setVendorEmail(vendor.getVendorEmail());
                dtoS.setVendor(vendorDTO);
            }
        } catch (Exception e) {
            System.out.println("Vendor not found for ID: " + schedulingBooking.getVendorId());
            dtoS.setVendor(null);
        }

        try {
            String vendorDriverServiceUrl = "https://api.worldtriplink.com/vendorDriver/" + schedulingBooking.getVendorDriverId();
            VendorDriver vendorDriver = restTemplate.getForObject(vendorDriverServiceUrl, VendorDriver.class);
            if (vendorDriver != null) {
                VendorDriverDTO driverDTO = new VendorDriverDTO();
                driverDTO.setVendorDriverId(vendorDriver.getVendorDriverId());
                driverDTO.setDriverName(vendorDriver.getDriverName());
                driverDTO.setContactNo(vendorDriver.getContactNo());
                driverDTO.setAltContactNo(vendorDriver.getAltContactNo());
                dtoS.setVendorDriver(driverDTO);
            }
        } catch (Exception e) {
            System.out.println("VendorDriver not found for ID: " + schedulingBooking.getVendorDriverId());
            dtoS.setVendorDriver(null);
        }

        try {
            String userServiceUrl = "https://api.worldtriplink.com/auth/getCarRentalUserById/" + schedulingBooking.getUser().getId();
            CarRentalUser user = restTemplate.getForObject(userServiceUrl, CarRentalUser.class);
            if (user != null) {
                UserDTO userDTO = new UserDTO();
                userDTO.setId(user.getId());
                userDTO.setUserName(user.getUserName());
                userDTO.setLastName(user.getLastName());
                userDTO.setEmail(user.getEmail());
                userDTO.setGender(user.getGender());
                userDTO.setPhone(user.getPhone());
                dtoS.setUser(userDTO);
            }
        } catch (Exception e) {
            System.out.println("User not found for ID: " + schedulingBooking.getUser().getId());
            dtoS.setUser(null);
        }

        List<ScheduleDateBookingDTO> scheduledDateDTOs = schedulingBooking.getScheduledDates().stream().map(sd -> {
            ScheduleDateBookingDTO sdDTO = new ScheduleDateBookingDTO();
            sdDTO.setId(sd.getId());
            sdDTO.setDate(sd.getDate());
            return sdDTO;
        }).toList();
        dtoS.setScheduledDates(scheduledDateDTOs);

        return dtoS;
    }

    public List<SchedulingBookingDTO> findByUserId(int userId) {
        List<SchedulingBooking> bookings = this.scheduleBookingRepository.findByCarRentalUserId(userId);
        List<SchedulingBookingDTO> dtoList = new ArrayList<>();

        for (SchedulingBooking schedulingBooking : bookings) {
            SchedulingBookingDTO schedulingBookingDTO = new SchedulingBookingDTO();
            schedulingBookingDTO.setId(schedulingBooking.getId());
            schedulingBookingDTO.setPickUpLocation(schedulingBooking.getPickUpLocation());
            schedulingBookingDTO.setDropLocation(schedulingBooking.getDropLocation());
            schedulingBookingDTO.setTime(schedulingBooking.getTime());
            schedulingBookingDTO.setReturnTime(schedulingBooking.getReturnTime());
            schedulingBookingDTO.setShiftTime(schedulingBooking.getShiftTime());
            schedulingBookingDTO.setBookingType(schedulingBooking.getBookingType());

            try {
                String vendorServiceUrl = "https://api.worldtriplink.com/vendors/" + schedulingBooking.getVendorId();
                Vendor vendor = restTemplate.getForObject(vendorServiceUrl, Vendor.class);
                if (vendor != null) {
                    VendorDTO vendorDTO = new VendorDTO();
                    vendorDTO.setId(vendor.getId());
                    vendorDTO.setVendorCompanyName(vendor.getVendorCompanyName());
                    vendorDTO.setContactNo(vendor.getContactNo());
                    vendorDTO.setAlternateMobileNo(vendor.getAlternateMobileNo());
                    vendorDTO.setCity(vendor.getCity());
                    vendorDTO.setVendorEmail(vendor.getVendorEmail());
                    schedulingBookingDTO.setVendor(vendorDTO);
                }
            } catch (Exception e) {
                System.out.println("Vendor not found for ID: " + schedulingBooking.getVendorId());
                schedulingBookingDTO.setVendor(null);
            }

            try {
                String vendorDriverServiceUrl = "https://api.worldtriplink.com/vendorDriver/" + schedulingBooking.getVendorDriverId();
                VendorDriver vendorDriver = restTemplate.getForObject(vendorDriverServiceUrl, VendorDriver.class);
                if (vendorDriver != null) {
                    VendorDriverDTO driverDTO = new VendorDriverDTO();
                    driverDTO.setVendorDriverId(vendorDriver.getVendorDriverId());
                    driverDTO.setDriverName(vendorDriver.getDriverName());
                    driverDTO.setContactNo(vendorDriver.getContactNo());
                    driverDTO.setAltContactNo(vendorDriver.getAltContactNo());
                    schedulingBookingDTO.setVendorDriver(driverDTO);
                }
            } catch (Exception e) {
                System.out.println("VendorDriver not found for ID: " + schedulingBooking.getVendorDriverId());
                schedulingBookingDTO.setVendorDriver(null);
            }

            try {
                String userServiceUrl = "https://api.worldtriplink.com/auth/getCarRentalUserById/" + userId;
                CarRentalUser user = restTemplate.getForObject(userServiceUrl, CarRentalUser.class);
                if (user != null) {
                    UserDTO userDTO = new UserDTO();
                    userDTO.setId(user.getId());
                    userDTO.setUserName(user.getUserName());
                    userDTO.setLastName(user.getLastName());
                    userDTO.setEmail(user.getEmail());
                    userDTO.setGender(user.getGender());
                    userDTO.setPhone(user.getPhone());
                    schedulingBookingDTO.setUser(userDTO);
                }
            } catch (Exception e) {
                System.out.println("User not found for ID: " + userId);
                schedulingBookingDTO.setUser(null);
            }

            List<ScheduleDateBookingDTO> scheduledDateDTOs = schedulingBooking.getScheduledDates().stream().map(sd -> {
                ScheduleDateBookingDTO sdDTO = new ScheduleDateBookingDTO();
                sdDTO.setId(sd.getId());
                sdDTO.setDate(sd.getDate());
                return sdDTO;
            }).toList();
            schedulingBookingDTO.setScheduledDates(scheduledDateDTOs);

            dtoList.add(schedulingBookingDTO);
        }

        return dtoList;
    }

    public List<SchedulingBookingDTO> getBookingByVendorDriverId(int vendorDriverId){

        List<SchedulingBooking> bookings = this.scheduleBookingRepository.findByVendorDriverId(vendorDriverId);
        List<SchedulingBookingDTO> dtoList = new ArrayList<>();

        for (SchedulingBooking schedulingBooking : bookings) {
            SchedulingBookingDTO schedulingBookingDTO = new SchedulingBookingDTO();
            schedulingBookingDTO.setId(schedulingBooking.getId());
            schedulingBookingDTO.setPickUpLocation(schedulingBooking.getPickUpLocation());
            schedulingBookingDTO.setDropLocation(schedulingBooking.getDropLocation());
            schedulingBookingDTO.setTime(schedulingBooking.getTime());
            schedulingBookingDTO.setReturnTime(schedulingBooking.getReturnTime());
            schedulingBookingDTO.setShiftTime(schedulingBooking.getShiftTime());
            schedulingBookingDTO.setBookingType(schedulingBooking.getBookingType());

            try {
                String vendorServiceUrl = "https://api.worldtriplink.com/vendors/" + schedulingBooking.getVendorId();
                Vendor vendor = restTemplate.getForObject(vendorServiceUrl, Vendor.class);
                if (vendor != null) {
                    VendorDTO vendorDTO = new VendorDTO();
                    vendorDTO.setId(vendor.getId());
                    vendorDTO.setVendorCompanyName(vendor.getVendorCompanyName());
                    vendorDTO.setContactNo(vendor.getContactNo());
                    vendorDTO.setAlternateMobileNo(vendor.getAlternateMobileNo());
                    vendorDTO.setCity(vendor.getCity());
                    vendorDTO.setVendorEmail(vendor.getVendorEmail());
                    schedulingBookingDTO.setVendor(vendorDTO);
                }
            } catch (Exception e) {
                System.out.println("Vendor not found for ID: " + schedulingBooking.getVendorId());
                schedulingBookingDTO.setVendor(null);
            }

            try {
                String vendorDriverServiceUrl = "https://api.worldtriplink.com/vendorDriver/" + schedulingBooking.getVendorDriverId();
                VendorDriver vendorDriver = restTemplate.getForObject(vendorDriverServiceUrl, VendorDriver.class);
                if (vendorDriver != null) {
                    VendorDriverDTO driverDTO = new VendorDriverDTO();
                    driverDTO.setVendorDriverId(vendorDriver.getVendorDriverId());
                    driverDTO.setDriverName(vendorDriver.getDriverName());
                    driverDTO.setContactNo(vendorDriver.getContactNo());
                    driverDTO.setAltContactNo(vendorDriver.getAltContactNo());
                    schedulingBookingDTO.setVendorDriver(driverDTO);
                }
            } catch (Exception e) {
                System.out.println("VendorDriver not found for ID: " + schedulingBooking.getVendorDriverId());
                schedulingBookingDTO.setVendorDriver(null);
            }

            try {
                String userServiceUrl = "https://api.worldtriplink.com/auth/getCarRentalUserById/" + schedulingBooking.getUser().getId();
                CarRentalUser user = restTemplate.getForObject(userServiceUrl, CarRentalUser.class);
                if (user != null) {
                    UserDTO userDTO = new UserDTO();
                    userDTO.setId(user.getId());
                    userDTO.setUserName(user.getUserName());
                    userDTO.setLastName(user.getLastName());
                    userDTO.setEmail(user.getEmail());
                    userDTO.setGender(user.getGender());
                    userDTO.setPhone(user.getPhone());
                    schedulingBookingDTO.setUser(userDTO);
                }
            } catch (Exception e) {
                System.out.println("User not found for ID: " + schedulingBooking.getUser().getId());
                schedulingBookingDTO.setUser(null);
            }

            List<ScheduleDateBookingDTO> scheduledDateDTOs = schedulingBooking.getScheduledDates().stream().map(sd -> {
                ScheduleDateBookingDTO sdDTO = new ScheduleDateBookingDTO();
                sdDTO.setId(sd.getId());
                sdDTO.setDate(sd.getDate());
                return sdDTO;
            }).toList();
            schedulingBookingDTO.setScheduledDates(scheduledDateDTOs);

            dtoList.add(schedulingBookingDTO);
        }

        return dtoList;
    }

    public ScheduledDate updateStatusByUserIdAndDate(int userId, LocalDate backendDate) {
        LocalDate currentDate = LocalDate.now();

        if (!currentDate.isEqual(backendDate)) {
            return null;
        }

        List<SchedulingBooking> bookings = scheduleBookingRepository.findByCarRentalUserId(userId);

        for (SchedulingBooking booking : bookings) {
            for (ScheduledDate schedulingDate : booking.getScheduledDates()) {
                if (schedulingDate.getDate().isEqual(currentDate)) {
                    schedulingDate.setStatus("COMPLETED");
                    return scheduleDates.save(schedulingDate);
                }
            }
        }

        return null;
    }

}