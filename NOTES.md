## step 0
* Installed Java from Oracle
    * This really means installing the JDK (java development kit) which contains the JRE and JVM
* Spring Boot projects can be set up with Spring Initializr, which is used through the IDE extension or the website
* docker-compose.yml configures what containers to start when docker compose up -d is run.
    * you can put variables for db and user which it will use to create db and user/role if there is no existing data directory
    * this lets someone reproduce your setup
    * docker compose ps for viewing containers and statuses
    * configured user for this project is postgres, container is local-postgres, DB is zero_budget
* This is all for setting up a web app

## step 1
* src/main/resources/db/migrations is where flyway looks for migrations by default
    * use migrations for db version control
    * name is prefix (V), version number (can have periods), two underscores, description, suffix (V0__init_schema.sql)
    * if testing, maybe use V0?
* SQL table design
    * in postgresql, autogenerate ids with GENERATED ALWAYS AS IDENTITY
    * UNIQUE can be used as constraint on multiple keys, multiple combinations, etc. and accepts nulls by default (can set NULLS NOT DISTINCT)
    * should always use TIMESTAMPTZ
    * remember foreign keys and indexes