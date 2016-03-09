/*
 * Copyright 2016 Temetra Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.temetra.vroomapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.temetra.vroomapi.model.RequestError;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.web.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@RestController
public class RouteController implements ErrorController {

    @Value("${vroom.binlocation}")
    private String vroomBinary;

    private static final Log log = LogFactory.getLog(RouteController.class);
    private ObjectMapper jsonMapper = new ObjectMapper();

    @RequestMapping(value = "/route", produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public JsonNode route(@RequestParam(value = "loc") String[] locs,
                        @RequestParam(value = "startAtFirst", defaultValue = "true") boolean startAtFirst,
                        @RequestParam(value = "endAtLast", defaultValue = "false") boolean endAtLast,
                        @RequestParam(value = "includeGeometry", defaultValue = "false") boolean includeGeometry)
                throws Exception {
        long millis = System.currentTimeMillis();

        File vroomBinFile = new File(vroomBinary);
        if(!vroomBinFile.exists()) {
            log.error("Vroom binary file doesn't exist");
            throw new Exception("Vroom binary file doesn't exist");
        }

        if(!vroomBinFile.canExecute()) {
            log.error("Cannot execute Vroom binary file");
            throw new Exception("Cannot execute Vroom binary file");
        }

        if(locs.length < 2) {
            log.error("Zero or one location sent");
            throw new Exception("Must send more than one location");
        }

        List<String> progArgs = new ArrayList<>();
        progArgs.add("./" + vroomBinFile.getName());
        if(startAtFirst){
            progArgs.add("-s");
        }
        if(endAtLast){
            progArgs.add("-e");
        }
        if(includeGeometry){
            progArgs.add("-g");
        }

        progArgs.add("loc=" + Joiner.on("&loc=").join(locs) + "");
        log.info("Run (" + millis + "): " + Joiner.on(' ').join(progArgs));

        StringBuilder output = new StringBuilder();
        ProcessBuilder builder = new ProcessBuilder(progArgs);
        builder.directory(vroomBinFile.getParentFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        try(BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
            process.waitFor();
        }

        log.info("Output (" + millis + "): " + output.toString());
        return jsonMapper.readTree(output.toString());
    }

    @Override
    public String getErrorPath() {
        return "/error";
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @RequestMapping(value = "/error", produces = MimeTypeUtils.APPLICATION_JSON_VALUE)
    public RequestError badRequest() {
        return new RequestError("Bad request");
    }

    @ExceptionHandler
    @RequestMapping(MimeTypeUtils.APPLICATION_JSON_VALUE)
    public RequestError error(final Exception e) {
        log.error("Exception when creating response", e);
        return new RequestError(e.getMessage());
    }

}
