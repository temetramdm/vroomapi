package com.temetra.vroomapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.temetra.vroomapi.model.RequestError;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@RestController
public class RouteController {

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

    @ExceptionHandler
    public RequestError error(final Exception e) {
        log.error("Exception when creating response", e);
        return new RequestError(e.getMessage());
    }

}
