package com.oddfar.campus.business.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateUnit;
import cn.hutool.core.date.DateUtil;
import cn.hutool.crypto.Mode;
import cn.hutool.crypto.Padding;
import cn.hutool.crypto.symmetric.AES;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.oddfar.campus.business.entity.IUser;
import com.oddfar.campus.business.mapper.IUserMapper;
import com.oddfar.campus.business.service.IMTLogFactory;
import com.oddfar.campus.business.service.IMTService;
import com.oddfar.campus.business.service.IShopService;
import com.oddfar.campus.business.service.IUserService;
import com.oddfar.campus.common.core.RedisCache;
import com.oddfar.campus.common.exception.ServiceException;
import com.oddfar.campus.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * i茅台服务实现类
 *
 * @author oddfar
 */
@Service
public class IMTServiceImpl implements IMTService {

    private static final Logger logger = LoggerFactory.getLogger(IMTServiceImpl.class);

    /**
     * 常量定义
     */
    private static final String SALT = "2af72f100c356273d46284f6fd1dfc08";
    private static final String AES_KEY = "qbhajinldepmucsonaaaccgypwuvcjaa";
    private static final String AES_IV = "2018534749963515";
    private static final String USER_AGENT = "iOS;16.3;Apple;?unrecognized?";
    private static final String MT_INFO_HEADER = "028e7f96f6369cafe1d105579c5b9377";
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String REDIS_KEY_MT_VERSION = "mt_version";
    private static final String APPLE_APP_URL = "https://apps.apple.com/cn/app/i%E8%8C%85%E5%8F%B0/id1600482450";
    private static final String VERSION_PATTERN = "new__latest__version\">(.*?)</p>";
    private static final String VERSION_REPLACE = "版本 ";
    
    /**
     * 业务常量
     */
    private static final int SUCCESS_CODE_2000 = 2000;
    private static final int SUCCESS_CODE_200 = 200;
    private static final int RESERVATION_STATUS_SUCCESS = 2;
    private static final int TRAVEL_STATUS_NOT_START = 1;
    private static final int TRAVEL_STATUS_IN_PROGRESS = 2;
    private static final int TRAVEL_STATUS_COMPLETED = 3;
    private static final int MIN_ENERGY_REQUIRED = 100;
    private static final int TRAVEL_START_HOUR = 9;
    private static final int TRAVEL_END_HOUR = 20;
    private static final int HOURS_24 = 24;
    private static final long DELAY_SECONDS_10 = 10L;
    private static final long DELAY_SECONDS_3 = 3L;
    private static final int MIN_REMAIN_CHANCE = 1;

    @Autowired
    private IUserMapper iUserMapper;

    @Autowired
    private RedisCache redisCache;

    @Autowired
    private IUserService iUserService;
    
    @Autowired
    private IShopService iShopService;

    @Autowired
    @Qualifier("imtExecutor")
    private Executor imtExecutor;

    /**
     * 项目启动时，初始化数据
     */
    @PostConstruct
    public void init() {
        imtExecutor.execute(() -> {
            try {
                logger.info("开始初始化i茅台数据");
                refreshAll();
                logger.info("i茅台数据初始化完成");
            } catch (Exception e) {
                logger.error("初始化i茅台数据失败", e);
            }
        });
    }


    @Override
    public String getMTVersion() {
        String mtVersion = Convert.toStr(redisCache.getCacheObject(REDIS_KEY_MT_VERSION));
        if (StringUtils.isNotEmpty(mtVersion)) {
            logger.debug("从缓存获取i茅台版本号: {}", mtVersion);
            return mtVersion;
        }
        try {
            logger.info("从Apple App Store获取i茅台版本号");
            String htmlContent = HttpUtil.get(APPLE_APP_URL);
            Pattern pattern = Pattern.compile(VERSION_PATTERN, Pattern.DOTALL);
            Matcher matcher = pattern.matcher(htmlContent);
            if (matcher.find()) {
                mtVersion = matcher.group(1).replace(VERSION_REPLACE, "");
            }
            if (StringUtils.isNotEmpty(mtVersion)) {
                redisCache.setCacheObject(REDIS_KEY_MT_VERSION, mtVersion);
                logger.info("成功获取i茅台版本号: {}", mtVersion);
            } else {
                logger.warn("未能从HTML中提取i茅台版本号");
            }
        } catch (Exception e) {
            logger.error("获取i茅台版本号失败", e);
        }
        return mtVersion;
    }

    @Override
    public void refreshMTVersion() {
        redisCache.deleteObject(REDIS_KEY_MT_VERSION);
        getMTVersion();
    }

    @Override
    public Boolean sendCode(String mobile, String deviceId) {
        Map<String, Object> data = new HashMap<>();
        data.put("mobile", mobile);
        final long curTime = System.currentTimeMillis();
        data.put("md5", signature(mobile, curTime));
        data.put("timestamp", String.valueOf(curTime));

        HttpRequest request = HttpUtil.createRequest(Method.POST,
                "https://app.moutai519.com.cn/xhr/front/user/register/vcode");
        setCommonHeaders(request, deviceId);
        request.header("Content-Type", CONTENT_TYPE_JSON);

        HttpResponse execute = request.body(JSONObject.toJSONString(data)).execute();
        JSONObject jsonObject = JSONObject.parseObject(execute.body());
        logger.info("「发送验证码返回」mobile: {}, response: {}", mobile, jsonObject.toJSONString());
        
        return validateAndReturnSuccess(jsonObject, "发送验证码", mobile);
    }

    @Override
    public boolean login(String mobile, String code, String deviceId) {
        Map<String, String> map = new HashMap<>();
        map.put("mobile", mobile);
        map.put("vCode", code);

        final long curTime = System.currentTimeMillis();
        map.put("md5", signature(mobile + code, curTime));
        map.put("timestamp", String.valueOf(curTime));
        map.put("MT-APP-Version", getMTVersion());

        HttpRequest request = HttpUtil.createRequest(Method.POST,
                "https://app.moutai519.com.cn/xhr/front/user/register/login");
        
        IUser user = iUserMapper.selectById(mobile);
        if (user != null) {
            deviceId = user.getDeviceId();
        }
        
        setCommonHeaders(request, deviceId);
        request.header("Content-Type", CONTENT_TYPE_JSON);

        HttpResponse execute = request.body(JSONObject.toJSONString(map)).execute();
        JSONObject body = JSONObject.parseObject(execute.body());

        if (validateSuccessResponse(body, "登录", mobile)) {
            iUserService.insertIUser(Long.parseLong(mobile), deviceId, body);
            logger.info("「登录成功」mobile: {}", mobile);
            return true;
        }
        return false;
    }


    @Override
    public void reservation(IUser iUser) {
        if (StringUtils.isEmpty(iUser.getItemCode())) {
            logger.warn("用户未配置预约商品，mobile: {}", iUser.getMobile());
            return;
        }
        String[] items = iUser.getItemCode().split("@");
        if (items.length == 0) {
            logger.warn("用户预约商品配置为空，mobile: {}", iUser.getMobile());
            return;
        }

        StringBuilder logContent = new StringBuilder();
        logContent.append(String.format("「开始预约」mobile: %s, 商品数量: %d\n", iUser.getMobile(), items.length));
        
        for (String itemId : items) {
            try {
                String shopId = iShopService.getShopId(iUser.getShopType(), itemId,
                        iUser.getProvinceName(), iUser.getCityName(), iUser.getLat(), iUser.getLng());
                JSONObject json = reservation(iUser, itemId, shopId);
                logContent.append(String.format("[预约项目]：%s\n[shopId]：%s\n[结果返回]：%s\n\n", 
                    itemId, shopId, json.toString()));
                logger.info("「预约成功」mobile: {}, itemId: {}, shopId: {}", iUser.getMobile(), itemId, shopId);
            } catch (Exception e) {
                logger.error("「预约失败」mobile: {}, itemId: {}", iUser.getMobile(), itemId, e);
                logContent.append(String.format("执行报错--[预约项目]：%s\n[结果返回]：%s\n\n", 
                    itemId, e.getMessage()));
            }
        }

        IMTLogFactory.reservation(iUser, logContent.toString());
        getEnergyAwardDelay(iUser);
    }

    /**
     * 延迟执行：获取申购耐力值，并记录日志
     *
     * @param iUser 用户信息
     */
    public void getEnergyAwardDelay(IUser iUser) {
        imtExecutor.execute(() -> {
            StringBuilder logContent = new StringBuilder();
            try {
                TimeUnit.SECONDS.sleep(DELAY_SECONDS_10);
                logger.debug("开始获取申购耐力值，mobile: {}", iUser.getMobile());
                String energyAward = getEnergyAward(iUser);
                logContent.append("[申购耐力值]:").append(energyAward);
                logger.info("「获取申购耐力值成功」mobile: {}, result: {}", iUser.getMobile(), energyAward);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("「获取申购耐力值被中断」mobile: {}", iUser.getMobile(), e);
                logContent.append("执行报错--[申购耐力值]:").append(e.getMessage());
            } catch (Exception e) {
                logger.error("「获取申购耐力值失败」mobile: {}", iUser.getMobile(), e);
                logContent.append("执行报错--[申购耐力值]:").append(e.getMessage());
            }
            IMTLogFactory.reservation(iUser, logContent.toString());
        });
    }
    /**
     * 领取小茅运
     *
     * @param iUser 用户信息
     */
    public void receiveReward(IUser iUser) {
        String url = "https://h5.moutai519.com.cn/game/xmTravel/receiveReward";
        HttpRequest request = HttpUtil.createRequest(Method.POST, url);
        setWapHeaders(request, iUser);

        HttpResponse execute = request.execute();
        JSONObject body = JSONObject.parseObject(execute.body());

        if (!validateSuccessResponse(body, "领取小茅运", iUser.getMobile())) {
            return;
        }
        logger.info("「领取小茅运成功」mobile: {}", iUser.getMobile());
    }

    /**
     * 获取申购耐力值
     *
     * @param iUser 用户信息
     * @return 响应结果
     */
    @Override
    public String getEnergyAward(IUser iUser) {
        String url = "https://h5.moutai519.com.cn/game/isolationPage/getUserEnergyAward";
        HttpRequest request = HttpUtil.createRequest(Method.POST, url);
        setWapHeaders(request, iUser);

        String body = request.execute().body();
        JSONObject jsonObject = JSONObject.parseObject(body);
        
        if (jsonObject.getInteger("code") == null || jsonObject.getInteger("code") != SUCCESS_CODE_200) {
            String message = jsonObject.getString("message");
            logger.error("「获取申购耐力值失败」mobile: {}, response: {}", iUser.getMobile(), body);
            throw new ServiceException(StringUtils.isNotEmpty(message) ? message : "获取申购耐力值失败");
        }

        return body;
    }

    @Override
    public void getTravelReward(IUser iUser) {
        StringBuilder logContent = new StringBuilder();
        try {
            logger.info("「开始获得旅行奖励」mobile: {}", iUser.getMobile());
            String s = travelReward(iUser);
            logContent.append("[获得旅行奖励]:").append(s);
            logger.info("「获得旅行奖励成功」mobile: {}, result: {}", iUser.getMobile(), s);
        } catch (Exception e) {
            logger.error("「获得旅行奖励失败」mobile: {}", iUser.getMobile(), e);
            logContent.append("执行报错--[获得旅行奖励]:").append(e.getMessage());
        }
        IMTLogFactory.reservation(iUser, logContent.toString());
    }

    /**
     * 获得旅行奖励
     *
     * @param iUser 用户信息
     * @return 旅行结果
     */
    public String travelReward(IUser iUser) {
        int hour = DateUtil.hour(new Date(), true);
        if (!(TRAVEL_START_HOUR <= hour && hour < TRAVEL_END_HOUR)) {
            throw new ServiceException("活动未开始，开始时间9点-20点");
        }
        
        Map<String, Integer> pageData = getUserIsolationPageData(iUser);
        Integer status = pageData.get("status");
        
        if (status == TRAVEL_STATUS_COMPLETED) {
            Integer currentPeriodCanConvertXmyNum = pageData.get("currentPeriodCanConvertXmyNum");
            Double travelRewardXmy = getXmTravelReward(iUser);
            receiveReward(iUser);
            
            if (travelRewardXmy > currentPeriodCanConvertXmyNum) {
                throw new ServiceException("当月无可领取奖励");
            }
        }

        Integer remainChance = pageData.get("remainChance");
        if (remainChance < MIN_REMAIN_CHANCE) {
            throw new ServiceException("当日旅行次数已耗尽");
        }
        
        return startTravel(iUser);
    }

    /**
     * 小茅运旅行活动
     *
     * @param iUser 用户信息
     * @return 旅行结果
     */
    public String startTravel(IUser iUser) {
        String url = "https://h5.moutai519.com.cn/game/xmTravel/startTravel";
        HttpRequest request = HttpUtil.createRequest(Method.POST, url);
        setWapHeadersWithoutLocation(request, iUser);
        
        String body = request.execute().body();
        JSONObject jsonObject = JSONObject.parseObject(body);
        
        if (!validateSuccessResponse(jsonObject, "开始旅行", iUser.getMobile())) {
            return null;
        }
        
        logger.info("「开始旅行成功」mobile: {}", iUser.getMobile());
        return jsonObject.toString();
    }


    /**
     * 查询可获取小茅运
     *
     * @param iUser 用户信息
     * @return 小茅运值
     */
    public Double getXmTravelReward(IUser iUser) {
        String url = "https://h5.moutai519.com.cn/game/xmTravel/getXmTravelReward";
        HttpRequest request = HttpUtil.createRequest(Method.GET, url);
        setWapHeadersWithoutLocation(request, iUser);
        
        String body = request.execute().body();
        JSONObject jsonObject = JSONObject.parseObject(body);
        
        if (!validateSuccessResponse(jsonObject, "查询小茅运", iUser.getMobile())) {
            return null;
        }
        
        Double reward = jsonObject.getJSONObject("data").getDouble("travelRewardXmy");
        logger.debug("「查询小茅运成功」mobile: {}, reward: {}", iUser.getMobile(), reward);
        return reward;
    }

    /**
     * 获取用户页面数据
     *
     * @param iUser 用户信息
     * @return 页面数据
     */
    public Map<String, Integer> getUserIsolationPageData(IUser iUser) {
        String url = "https://h5.moutai519.com.cn/game/isolationPage/getUserIsolationPageData";
        HttpRequest request = HttpUtil.createRequest(Method.GET, url);
        setWapHeadersWithoutLocation(request, iUser);
        
        String body = request.form("__timestamp", DateUtil.currentSeconds()).execute().body();
        JSONObject jsonObject = JSONObject.parseObject(body);
        
        if (!validateSuccessResponse(jsonObject, "获取用户页面数据", iUser.getMobile())) {
            return null;
        }
        
        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null) {
            logger.error("「获取用户页面数据失败」mobile: {}, data为空", iUser.getMobile());
            throw new ServiceException("获取用户页面数据失败，数据为空");
        }
        
        int energy = data.getIntValue("energy");
        JSONObject xmTravel = data.getJSONObject("xmTravel");
        JSONObject energyReward = data.getJSONObject("energyReward");
        
        if (xmTravel == null || energyReward == null) {
            logger.error("「获取用户页面数据失败」mobile: {}, xmTravel或energyReward为空", iUser.getMobile());
            throw new ServiceException("获取用户页面数据失败，必要数据为空");
        }
        
        Integer status = xmTravel.getInteger("status");
        Long travelEndTime = xmTravel.getLong("travelEndTime");
        int remainChance = xmTravel.getIntValue("remainChance");
        Integer energyValue = energyReward.getInteger("value");

        if (energyValue != null && energyValue > 0) {
            logger.debug("检测到可领取耐力值奖励，mobile: {}, energyValue: {}", iUser.getMobile(), energyValue);
            getEnergyAward(iUser);
            energy += energyValue;
        }

        int exchangeRateInfo = getExchangeRateInfo(iUser);
        if (exchangeRateInfo <= 0) {
            logger.warn("「当月无可领取奖励」mobile: {}", iUser.getMobile());
            throw new ServiceException("当月无可领取奖励");
        }

        Long endTime = travelEndTime * 1000;
        if (status == TRAVEL_STATUS_NOT_START) {
            if (energy < MIN_ENERGY_REQUIRED) {
                String message = String.format("耐力不足100, 当前耐力值:%s", energy);
                logger.warn("「耐力不足」mobile: {}, energy: {}", iUser.getMobile(), energy);
                throw new ServiceException(message);
            }
        }
        
        if (status == TRAVEL_STATUS_IN_PROGRESS) {
            Date date = new Date(endTime);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String formattedDate = sdf.format(date);
            String message = String.format("旅行暂未结束,本次旅行结束时间:%s ", formattedDate);
            logger.warn("「旅行进行中」mobile: {}, endTime: {}", iUser.getMobile(), formattedDate);
            throw new ServiceException(message);
        }
        
        Map<String, Integer> map = new HashMap<>();
        map.put("remainChance", remainChance);
        map.put("status", status);
        map.put("currentPeriodCanConvertXmyNum", exchangeRateInfo);
        logger.debug("「获取用户页面数据成功」mobile: {}, remainChance: {}, status: {}", 
            iUser.getMobile(), remainChance, status);
        return map;
    }

    /**
     * 获取本月剩余奖励耐力值
     *
     * @param iUser 用户信息
     * @return 剩余奖励耐力值
     */
    public int getExchangeRateInfo(IUser iUser) {
        String url = "https://h5.moutai519.com.cn/game/synthesize/exchangeRateInfo";
        HttpRequest request = HttpUtil.createRequest(Method.GET, url);
        setWapHeadersWithoutLocation(request, iUser);

        String body = request.form("__timestamp", DateUtil.currentSeconds()).execute().body();
        JSONObject jsonObject = JSONObject.parseObject(body);
        
        if (!validateSuccessResponse(jsonObject, "获取剩余奖励耐力值", iUser.getMobile())) {
            return 0;
        }
        
        int exchangeRateInfo = jsonObject.getJSONObject("data").getIntValue("currentPeriodCanConvertXmyNum");
        logger.debug("「获取剩余奖励耐力值成功」mobile: {}, exchangeRateInfo: {}", iUser.getMobile(), exchangeRateInfo);
        return exchangeRateInfo;
    }

    @Async
    @Override
    public void reservationBatch() {
        int minute = DateUtil.minute(new Date());
        List<IUser> iUsers = iUserService.selectReservationUserByMinute(minute);
        
        if (iUsers == null || iUsers.isEmpty()) {
            logger.debug("「批量预约」当前分钟({})无待预约用户", minute);
            return;
        }
        
        logger.info("「批量预约开始」当前分钟: {}, 用户数量: {}", minute, iUsers.size());

        for (IUser iUser : iUsers) {
            logger.info("「开始预约用户」mobile: {}", iUser.getMobile());
            try {
                reservation(iUser);
                TimeUnit.SECONDS.sleep(DELAY_SECONDS_3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("「批量预约被中断」当前分钟: {}", minute, e);
                break;
            } catch (Exception e) {
                logger.error("「预约用户失败」mobile: {}", iUser.getMobile(), e);
            }
        }
        logger.info("「批量预约结束」当前分钟: {}, 处理用户数: {}", minute, iUsers.size());
    }

    @Async
    @Override
    public void getTravelRewardBatch() {
        try {
            int minute = DateUtil.minute(new Date());
            List<IUser> iUsers = iUserService.selectReservationUserByMinute(minute);
            
            if (iUsers == null || iUsers.isEmpty()) {
                logger.debug("「批量获得旅行奖励」当前分钟({})无待处理用户", minute);
                return;
            }
            
            logger.info("「批量获得旅行奖励开始」当前分钟: {}, 用户数量: {}", minute, iUsers.size());

            for (IUser iUser : iUsers) {
                logger.info("「开始获得旅行奖励」mobile: {}", iUser.getMobile());
                try {
                    getTravelReward(iUser);
                    TimeUnit.SECONDS.sleep(DELAY_SECONDS_3);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("「批量获得旅行奖励被中断」当前分钟: {}", minute, e);
                    break;
                } catch (Exception e) {
                    logger.error("「获得旅行奖励失败」mobile: {}", iUser.getMobile(), e);
                }
            }
            logger.info("「批量获得旅行奖励结束」当前分钟: {}, 处理用户数: {}", minute, iUsers.size());
        } catch (Exception e) {
            logger.error("「批量获得旅行奖励异常」", e);
        }
    }

    @Override
    public void refreshAll() {
        refreshMTVersion();
        iShopService.refreshShop();
        iShopService.refreshItem();
    }

    @Override
    public void appointmentResults() {
        logger.info("「申购结果查询开始」");
        List<IUser> iUsers = iUserService.selectReservationUser();
        
        if (iUsers == null || iUsers.isEmpty()) {
            logger.info("「申购结果查询」无待查询用户");
            return;
        }
        
        logger.info("「申购结果查询」待查询用户数: {}", iUsers.size());
        
        int successCount = 0;
        int failCount = 0;
        
        for (IUser iUser : iUsers) {
            try {
                String url = "https://app.moutai519.com.cn/xhr/front/mall/reservation/list/pageOne/query";
                HttpRequest request = HttpUtil.createRequest(Method.GET, url);
                setCommonHeaders(request, iUser.getDeviceId());
                request.header("MT-Token", iUser.getToken());
                
                String body = request.execute().body();
                JSONObject jsonObject = JSONObject.parseObject(body);
                logger.debug("「查询申购结果」mobile: {}, response: {}", iUser.getMobile(), body);
                
                if (!validateSuccessResponseSilent(jsonObject, "查询申购结果", iUser.getMobile())) {
                    failCount++;
                    continue;
                }
                
                JSONArray itemVOs = jsonObject.getJSONObject("data").getJSONArray("reservationItemVOS");
                if (Objects.isNull(itemVOs) || itemVOs.isEmpty()) {
                    logger.debug("「申购记录为空」mobile: {}", iUser.getMobile());
                    continue;
                }
                
                for (Object itemVO : itemVOs) {
                    JSONObject item = JSON.parseObject(itemVO.toString());
                    if (item.getInteger("status") == RESERVATION_STATUS_SUCCESS 
                            && DateUtil.between(item.getDate("reservationTime"), new Date(), DateUnit.HOUR) < HOURS_24) {
                        String logContent = String.format("%s 申购%s成功", 
                            DateUtil.formatDate(item.getDate("reservationTime")), 
                            item.getString("itemName"));
                        IMTLogFactory.reservation(iUser, logContent);
                        logger.info("「申购成功」mobile: {}, itemName: {}, reservationTime: {}", 
                            iUser.getMobile(), item.getString("itemName"), 
                            DateUtil.formatDate(item.getDate("reservationTime")));
                        successCount++;
                    }
                }
            } catch (Exception e) {
                logger.error("「查询申购结果异常」mobile: {}", iUser.getMobile(), e);
                failCount++;
            }
        }
        logger.info("「申购结果查询结束」总用户数: {}, 成功数: {}, 失败数: {}", 
            iUsers.size(), successCount, failCount);
    }

    /**
     * 预约商品
     *
     * @param iUser 用户信息
     * @param itemId 商品ID
     * @param shopId 门店ID
     * @return 预约结果
     */
    public JSONObject reservation(IUser iUser, String itemId, String shopId) {
        Map<String, Object> map = new HashMap<>();
        JSONArray itemArray = new JSONArray();
        Map<String, Object> info = new HashMap<>();
        info.put("count", 1);
        info.put("itemId", itemId);
        itemArray.add(info);

        map.put("itemInfoList", itemArray);
        map.put("sessionId", iShopService.getCurrentSessionId());
        map.put("userId", iUser.getUserId().toString());
        map.put("shopId", shopId);
        map.put("actParam", AesEncrypt(JSON.toJSONString(map)));

        HttpRequest request = HttpUtil.createRequest(Method.POST,
                "https://app.moutai519.com.cn/xhr/front/mall/reservation/add");
        setCommonHeaders(request, iUser.getDeviceId());
        request.header("MT-Lat", iUser.getLat());
        request.header("MT-Lng", iUser.getLng());
        request.header("MT-Token", iUser.getToken());
        request.header("MT-Info", MT_INFO_HEADER);
        request.header("Content-Type", CONTENT_TYPE_JSON);
        request.header("userId", iUser.getUserId().toString());

        HttpResponse execute = request.body(JSONObject.toJSONString(map)).execute();
        JSONObject body = JSONObject.parseObject(execute.body());
        
        if (!validateSuccessResponse(body, "预约商品", iUser.getMobile())) {
            return null;
        }
        
        logger.info("「预约商品成功」mobile: {}, itemId: {}, shopId: {}", 
            iUser.getMobile(), itemId, shopId);
        return body;
    }

    /**
     * AES加密
     *
     * @param params 待加密参数
     * @return 加密后的Base64字符串
     */
    public static String AesEncrypt(String params) {
        AES aes = new AES(Mode.CBC, Padding.PKCS5Padding, AES_KEY.getBytes(), AES_IV.getBytes());
        return aes.encryptBase64(params);
    }

    /**
     * AES解密
     *
     * @param params 待解密参数
     * @return 解密后的字符串
     */
    public static String AesDecrypt(String params) {
        AES aes = new AES(Mode.CBC, Padding.PKCS5Padding, AES_KEY.getBytes(), AES_IV.getBytes());
        return aes.decryptStr(params);
    }

    /**
     * 获取MD5签名
     * 验证码签名：密钥+手机号+时间
     * 登录签名：密钥+mobile+vCode+时间
     *
     * @param content 签名内容
     * @param time 时间戳
     * @return MD5签名
     */
    private static String signature(String content, long time) {
        String text = SALT + content + time;
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(text.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("MD5签名失败", e);
            throw new ServiceException("签名生成失败");
        }
    }

    /**
     * 设置通用请求头（App接口）
     *
     * @param request HTTP请求
     * @param deviceId 设备ID
     */
    private void setCommonHeaders(HttpRequest request, String deviceId) {
        request.header("MT-Device-ID", deviceId);
        request.header("MT-APP-Version", getMTVersion());
        request.header("User-Agent", USER_AGENT);
    }

    /**
     * 设置Wap请求头（包含位置信息）
     *
     * @param request HTTP请求
     * @param iUser 用户信息
     */
    private void setWapHeaders(HttpRequest request, IUser iUser) {
        setCommonHeaders(request, iUser.getDeviceId());
        request.header("MT-Lat", iUser.getLat());
        request.header("MT-Lng", iUser.getLng());
        request.cookie("MT-Token-Wap=" + iUser.getCookie() + ";MT-Device-ID-Wap=" + iUser.getDeviceId() + ";");
    }

    /**
     * 设置Wap请求头（不包含位置信息）
     *
     * @param request HTTP请求
     * @param iUser 用户信息
     */
    private void setWapHeadersWithoutLocation(HttpRequest request, IUser iUser) {
        setCommonHeaders(request, iUser.getDeviceId());
        request.cookie("MT-Token-Wap=" + iUser.getCookie() + ";MT-Device-ID-Wap=" + iUser.getDeviceId() + ";");
    }

    /**
     * 验证响应是否成功（返回Boolean）
     *
     * @param jsonObject 响应JSON对象
     * @param operation 操作名称
     * @param mobile 手机号（用于日志）
     * @return 是否成功
     */
    private Boolean validateAndReturnSuccess(JSONObject jsonObject, String operation, String mobile) {
        if (String.valueOf(SUCCESS_CODE_2000).equals(jsonObject.getString("code"))) {
            return Boolean.TRUE;
        } else {
            String errorMsg = jsonObject.getString("message");
            logger.error("「{}失败」mobile: {}, response: {}", operation, mobile, jsonObject.toJSONString());
            throw new ServiceException(StringUtils.isNotEmpty(errorMsg) ? errorMsg : operation + "失败");
        }
    }

    /**
     * 验证响应是否成功（返回boolean，失败时抛出异常）
     *
     * @param jsonObject 响应JSON对象
     * @param operation 操作名称
     * @param mobile 手机号（用于日志）
     * @return 是否成功
     * @throws ServiceException 失败时抛出异常
     */
    private boolean validateSuccessResponse(JSONObject jsonObject, String operation, String mobile) {
        if (String.valueOf(SUCCESS_CODE_2000).equals(jsonObject.getString("code"))) {
            return true;
        } else {
            String errorMsg = jsonObject.getString("message");
            logger.error("「{}失败」mobile: {}, response: {}", operation, mobile, jsonObject.toJSONString());
            throw new ServiceException(StringUtils.isNotEmpty(errorMsg) ? errorMsg : operation + "失败");
        }
    }

    /**
     * 验证响应是否成功（不抛出异常，返回boolean）
     *
     * @param jsonObject 响应JSON对象
     * @param operation 操作名称
     * @param mobile 手机号（用于日志）
     * @return 是否成功
     */
    private boolean validateSuccessResponseSilent(JSONObject jsonObject, String operation, String mobile) {
        if (jsonObject.getInteger("code") != null && 
            (jsonObject.getInteger("code") == SUCCESS_CODE_2000 || jsonObject.getInteger("code") == SUCCESS_CODE_200)) {
            return true;
        } else {
            String errorMsg = jsonObject.getString("message");
            logger.warn("「{}失败」mobile: {}, message: {}", operation, mobile, errorMsg);
            return false;
        }
    }
}
