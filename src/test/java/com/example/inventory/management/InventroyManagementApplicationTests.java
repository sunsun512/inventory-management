package com.example.inventory.management;

import com.example.inventory.management.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class InventroyManagementApplicationTests extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InventroyManagementApplicationTests.class);

    @Test
    void 컨텍스트가_정상적으로_로드된다() {
        log.info("Spring 애플리케이션 컨텍스트 로드 확인 완료");
    }

}
