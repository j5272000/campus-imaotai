package com.oddfar.campus.business.task;

import com.oddfar.campus.business.service.IMTService;
import com.oddfar.campus.business.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * i茅台定时任务
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class CampusIMTTask {
    private static final Logger logger = LoggerFactory.getLogger(CampusIMTTask.class);

    private final IMTService imtService;

    private final IUserService iUserService;


    /**
     * 1：10 批量修改用户随机预约的时间
     */
    @Async
    @Scheduled(cron = "0 10 1 ? * * ")
    public void updateUserMinuteBatch() {
        logger.info("「定时任务」开始批量修改用户预约时间");
        try {
            iUserService.updateUserMinuteBatch();
            logger.info("「定时任务」批量修改用户预约时间完成");
        } catch (Exception e) {
            logger.error("「定时任务」批量修改用户预约时间失败", e);
        }
    }

    /**
     * 11点期间，每分钟执行一次批量获得旅行奖励
     */
    @Async
    @Scheduled(cron = "0 0/1 11 ? * *")
    public void getTravelRewardBatch() {
        logger.debug("「定时任务」开始批量获得旅行奖励");
        try {
            imtService.getTravelRewardBatch();
        } catch (Exception e) {
            logger.error("「定时任务」批量获得旅行奖励失败", e);
        }
    }

    /**
     * 9点期间，每分钟执行一次批量预约
     */
    @Async
    @Scheduled(cron = "0 0/1 9 ? * *")
    public void reservationBatchTask() {
        logger.debug("「定时任务」开始批量预约");
        try {
            imtService.reservationBatch();
        } catch (Exception e) {
            logger.error("「定时任务」批量预约失败", e);
        }
    }

    /**
     * 7点和8点的10分、55分刷新数据
     */
    @Async
    @Scheduled(cron = "0 10,55 7,8 ? * * ")
    public void refresh() {
        logger.info("「定时任务」开始刷新数据（版本号、预约item、门店shop列表）");
        try {
            imtService.refreshAll();
            logger.info("「定时任务」刷新数据完成");
        } catch (Exception e) {
            logger.error("「定时任务」刷新数据失败", e);
        }
    }

    /**
     * 18:05分获取申购结果
     */
    @Async
    @Scheduled(cron = "0 5 18 ? * * ")
    public void appointmentResults() {
        logger.info("「定时任务」开始查询申购结果");
        try {
            imtService.appointmentResults();
            logger.info("「定时任务」查询申购结果完成");
        } catch (Exception e) {
            logger.error("「定时任务」查询申购结果失败", e);
        }
    }


}