/*
 * Copyright 2018 Temetra Ltd.
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

package com.temetra.vroomapi

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.common.base.Joiner
import org.apache.commons.logging.LogFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.web.ErrorController
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.util.MimeTypeUtils
import org.springframework.web.bind.annotation.*
import java.io.File
import java.util.*
import java.util.concurrent.atomic.AtomicLong

@RestController
class RouteController : ErrorController {

    companion object {
        val log = LogFactory.getLog(RouteController::class.java)!!
        val counter = AtomicLong(1)
    }

    /* The path to the VROOM binary file (from application.properties)  */
    @Value("\${vroom.binlocation}")
    lateinit var vroomBinary: String

    @Value("\${vroom.use-osrm-lib:true}")
    var vroomUseOsrmLib: Boolean = true

    private val jsonMapper = ObjectMapper()
    init {
        jsonMapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
    }

    /**
     * The primary method, which handles taking in an array of coordinates along with some options, and
     * returns the JSON response directly from the VROOM binary
     *
     * @param locs list of comma-separated lon-lat coordinates
     * @param start the start coord, comma-separated lon-lat
     * @param end the end coord, comma-separated lon-lat
     * @param includeGeometry true or false, whether we want to output route geometry or not
     */
    @RequestMapping(value = "/route", produces = arrayOf(MimeTypeUtils.APPLICATION_JSON_VALUE))
    @ResponseBody
    @Throws(Exception::class)
    fun route(@RequestParam(value = "loc") locs: Array<String>,
              @RequestParam start: String,
              @RequestParam(required = false) end: String?,
              @RequestParam(defaultValue = "false") includeGeometry: Boolean): ResponseEntity<String> {
        log.debug("Executing request with params vroomUseOsrmLib: [$vroomUseOsrmLib]")
        val runCount = counter.getAndIncrement()

        var vroomBinFile:File? = null
        // make sure we can access and execute the binary
        if (vroomUseOsrmLib) {
            vroomBinFile = File(vroomBinary)
            if (!vroomBinFile.exists()) {
                throw Exception("VROOM binary file doesn't exist")
            }
            if (!vroomBinFile.canExecute()) {
                throw Exception("Cannot execute VROOM binary file")
            }
        }

        // make sure we have some valid locations
        val lonLats = arrayListOf<Pair<Double, Double>>()
        for(loc in locs) {
            val lonLat = loc.split(",")
            if(lonLat.size != 2) {
                throw Exception("Need both longitude and latitude for coord: $loc")
            }
            try {
                lonLats.add(Pair(lonLat[0].toDouble(), lonLat[1].toDouble()))
            } catch (e: Exception) {
                throw Exception("Invalid coord: $loc")
            }
        }

        // validate the start location
        val startLonLat: Array<Double>
        var lonLat = start.split(",")
        if(lonLat.size != 2) {
            throw Exception("Need both longitude and latitude for start coord: $start")
        }
        try {
            startLonLat = arrayOf(lonLat[0].toDouble(), lonLat[1].toDouble())
        } catch (e: Exception) {
            throw Exception("Invalid start coord: $start")
        }

        // validate the end location
        var endLonLat: Array<Double>? = null
        if(end != null) {
            lonLat = end.split(",")
            if (lonLat.size != 2) {
                throw Exception("Need both longitude and latitude for end coord: $end")
            }
            try {
                endLonLat = arrayOf(lonLat[0].toDouble(), lonLat[1].toDouble())
            } catch (e: Exception) {
                throw Exception("Invalid end coord: $end")
            }
        }

        // construct the arguments
        val progArgs = arrayListOf<String>()
        if (vroomUseOsrmLib) {
            progArgs.add(vroomBinFile?.absolutePath!!)
            progArgs.add("-l") // use libosrm
        }
        progArgs.add("-t " + Runtime.getRuntime().availableProcessors())
        if (includeGeometry) {
            progArgs.add("-g")
        }

        // start building the objects for sending to VROOM
        val vehicle = Vehicle(0, startLonLat, endLonLat)
        val jobs = arrayListOf<Job>()
        for(i in lonLats.indices) {
            val coord = lonLats[i]
            jobs.add(Job(i, arrayOf(coord.first, coord.second)))
        }
        val computeRequest = ComputeRequest(listOf(vehicle), jobs)

        // save to temp file
        val tempFile = File.createTempFile("vroom_", null)
        tempFile.deleteOnExit()
        tempFile.printWriter().use { out ->
            out.println(jsonMapper.writeValueAsString(computeRequest))
        }
        progArgs.add("-i " + tempFile.absoluteFile)
        log.debug("Run (" + runCount + "): " + Joiner.on(' ').join(progArgs))

        val process = Runtime.getRuntime().exec(Joiner.on(' ').join(progArgs))
        var scanner = Scanner(process.inputStream)
        if(scanner.hasNextLine()) {
            val output = scanner.nextLine()
            log.debug("Success output ($runCount): $output")
            return ResponseEntity.ok(output)
        } else {
            scanner = Scanner(process.errorStream)
            val output = scanner.nextLine()
            log.debug("Error output ($runCount): $output")
            throw Exception("Error output from VROOM binary: $output")
        }
    }

    /**
     * Override the default Spring 400 error handler when, for example, a required request param is not present
     * so we just return a JSON error instead
     */
    override fun getErrorPath(): String {
        return "/error"
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @RequestMapping(value = "/error", produces = arrayOf(MimeTypeUtils.APPLICATION_JSON_VALUE))
    fun badRequest(): RequestError {
        return RequestError("Bad request")
    }

    /**
     * Generic exception handler to simply return a JSON object with a single attribute for error description
     */
    @ExceptionHandler
    fun error(e: Exception): RequestError {
        log.error("Exception when creating response", e)
        return RequestError(e.message)
    }

    /**
     * Simple wrapper POJO that's transformed into JSON by Spring
     */
    data class RequestError(val error: String?)

    /**
     * POJO that holds the required information for a JSON request to be sent to the VROOM binary
     */
    data class ComputeRequest(val vehicles: List<Vehicle>, val jobs: List<Job>)

    @Suppress("MemberVisibilityCanPrivate")
    data class Vehicle(val id: Int, val start: Array<Double>, val end: Array<Double>?) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Vehicle

            if (id != other.id) return false
            if (!Arrays.equals(start, other.start)) return false
            if (!Arrays.equals(end, other.end)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + Arrays.hashCode(start)
            result = 31 * result + (end?.let { Arrays.hashCode(it) } ?: 0)
            return result
        }
    }

    @Suppress("MemberVisibilityCanPrivate")
    data class Job(val id: Int, val location: Array<Double>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Job

            if (id != other.id) return false
            if (!Arrays.equals(location, other.location)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = id
            result = 31 * result + Arrays.hashCode(location)
            return result
        }
    }

}