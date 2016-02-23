# VROOM Web API

A simple Spring Boot Java app which returns JSON generated by [VROOM](https://github.com/jcoupey/vroom).

### Instructions

Edit `vroom.binlocation` in application.properties (src/main/resources) to point to the location of your VROOM executable.

Build the JAR file by running `mvn clean package` in the project directory.

Run the app using:
`java -jar vroomapi-1.2.jar > /dev/null 2>&1 &`

The app will log to vroom.log in the same directory as the JAR, assuming correct file permissions on the parent folder.
