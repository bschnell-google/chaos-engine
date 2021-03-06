/*
 *    Copyright (c) 2018 - 2020, Thales DIS CPL Canada, Inc
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 *
 */

package com.thales.chaos.admin;

import com.thales.chaos.admin.enums.AdminState;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin")
@Tag(name = AdminController.ADMIN)
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    public static final String ADMIN = "Admin";
    @Autowired
    private AdminManager adminManager;

    @Operation(summary = "Get Running State",
               description = "Get the current administrative state of the Chaos Engine",
               responses = {
                       @ApiResponse(description = "The current admin state",
                                    content = @Content(schema = @Schema(implementation = AdminState.class)))
               })
    @GetMapping("/state")
    public AdminState getAdminState () {
        return adminManager.getAdminState();
    }

    @Operation(summary = "Set Running State",
               description = "Controls whether or not the Chaos Engine will take any action on endpoints. In a STARTED state, all actions can be performed. In a DRAIN state, no new experiments can be created, but existing experiments can still self-heal and finalize. " + "In a PAUSED state, no activity is done.",
               responses = {
                       @ApiResponse(description = "The admin state has been changed",
                                    content = @Content(schema = @Schema()))
               })
    @PostMapping("/state")
    public void setAdminState (@Parameter(description = "The New Admin State.", required = true) @RequestParam("state")
                                       AdminState newAdminState) {
        log.info("Setting admin state to {}", newAdminState);
        adminManager.setAdminState(newAdminState);
    }
}
