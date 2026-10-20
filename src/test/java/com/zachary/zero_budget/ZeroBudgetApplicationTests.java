package com.zachary.zero_budget;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

// Without the container import this test would connect to the docker-compose database.
@SpringBootTest
@Import(PostgresContainerConfig.class)
class ZeroBudgetApplicationTests {

	@Test
	void contextLoads() {
	}

}
