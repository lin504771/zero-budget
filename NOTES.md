# step 0
* Installed Java from Oracle
    * This really means installing the JDK (java development kit) which contains the JRE and JVM
* Spring Boot projects can be set up with Spring Initializr, which is used through the IDE extension or the website
* docker-compose.yml configures what containers to start when docker compose up -d is run.
    * you can put variables for db and user which it will use to create db and user/role if there is no existing data directory
    * this lets someone reproduce your setup
    * docker compose ps for viewing containers and statuses
* This is all for setting up a web app