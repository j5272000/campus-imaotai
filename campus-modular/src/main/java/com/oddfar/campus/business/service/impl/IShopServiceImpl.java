package com.oddfar.campus.business.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.oddfar.campus.business.domain.IMTItemInfo;
import com.oddfar.campus.business.domain.MapPoint;
import com.oddfar.campus.business.entity.IItem;
import com.oddfar.campus.business.entity.IShop;
import com.oddfar.campus.business.mapper.IItemMapper;
import com.oddfar.campus.business.mapper.IShopMapper;
import com.oddfar.campus.business.service.IShopService;
import com.oddfar.campus.common.core.RedisCache;
import com.oddfar.campus.common.exception.ServiceException;
import com.oddfar.campus.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class IShopServiceImpl extends ServiceImpl<IShopMapper, IShop> implements IShopService {

    private static final Logger logger = LoggerFactory.getLogger(IShopServiceImpl.class);

    @Autowired
    IShopMapper iShopMapper;
    @Autowired
    IItemMapper iItemMapper;

    @Autowired
    RedisCache redisCache;

    @Override
    public List<IShop> selectShopList() {
        List<IShop> shopList = redisCache.getCacheList("mt_shop_list");

        if (shopList != null && !shopList.isEmpty()) {
            logger.debug("从缓存获取门店列表，数量: {}", shopList.size());
            return shopList;
        }

        logger.info("缓存中无门店列表，开始刷新");
        refreshShop();
        shopList = iShopMapper.selectList();
        logger.info("获取门店列表完成，数量: {}", shopList != null ? shopList.size() : 0);
        return shopList;
    }

    @Override
    public void refreshShop() {
        try {
            logger.info("开始刷新门店列表");
            HttpRequest request = HttpUtil.createRequest(Method.GET,
                    "https://static.moutai519.com.cn/mt-backend/xhr/front/mall/resource/get");

            HttpResponse response = request.execute();
            String responseBody = response.body();
            if (StringUtils.isEmpty(responseBody)) {
                throw new ServiceException("获取门店资源URL失败，响应为空");
            }
            
            JSONObject body = JSONObject.parseObject(responseBody);
            JSONObject data = body.getJSONObject("data");
            if (data == null || !data.containsKey("mtshops_pc")) {
                throw new ServiceException("获取门店资源URL失败，响应数据异常");
            }
            String shopUrl = data.getJSONObject("mtshops_pc").getString("url");
            if (StringUtils.isEmpty(shopUrl)) {
                throw new ServiceException("门店资源URL为空");
            }
            
            //清空数据库
            iShopMapper.truncateShop();
            redisCache.deleteObject("mt_shop_list");
            logger.debug("已清空门店数据库和缓存");

            String shopData = HttpUtil.get(shopUrl);
            if (StringUtils.isEmpty(shopData)) {
                throw new ServiceException("获取门店数据失败，返回为空");
            }

            JSONObject jsonObject = JSONObject.parseObject(shopData);
            Set<String> shopIdSet = jsonObject.keySet();
            List<IShop> list = new ArrayList<>();
            for (String iShopId : shopIdSet) {
                JSONObject shop = jsonObject.getJSONObject(iShopId);
                IShop iShop = new IShop(iShopId, shop);
                list.add(iShop);
            }
            
            if (!list.isEmpty()) {
                this.saveBatch(list);
                redisCache.setCacheList("mt_shop_list", list);
                logger.info("门店列表刷新完成，共{}个门店", list.size());
            } else {
                logger.warn("门店列表为空，未刷新数据");
            }
        } catch (Exception e) {
            logger.error("刷新门店列表失败", e);
            throw new ServiceException("刷新门店列表失败: " + e.getMessage());
        }
    }

    @Override
    public String getCurrentSessionId() {
        String mtSessionId = Convert.toStr(redisCache.getCacheObject("mt_session_id"));

        if (StringUtils.isNotEmpty(mtSessionId)) {
            logger.debug("从缓存获取SessionId: {}", mtSessionId);
            return mtSessionId;
        }

        try {
            logger.info("缓存中无SessionId，开始获取");
            long dayTime = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.of("+8")).toEpochMilli();
            String url = "https://static.moutai519.com.cn/mt-backend/xhr/front/mall/index/session/get/" + dayTime;
            String res = HttpUtil.get(url);
            
            if (StringUtils.isEmpty(res)) {
                throw new ServiceException("获取SessionId失败，响应为空");
            }
            
            JSONObject jsonObject = JSONObject.parseObject(res);

            String code = jsonObject.getString("code");
            if (!"2000".equals(code)) {
                String message = jsonObject.getString("message");
                throw new ServiceException(StringUtils.isNotEmpty(message) ? message : "获取SessionId失败");
            }
            
            JSONObject data = jsonObject.getJSONObject("data");
            if (data == null) {
                throw new ServiceException("获取SessionId失败，响应数据为空");
            }
            
            mtSessionId = data.getString("sessionId");
            if (StringUtils.isEmpty(mtSessionId)) {
                throw new ServiceException("SessionId为空");
            }
            redisCache.setCacheObject("mt_session_id", mtSessionId);
            logger.info("成功获取SessionId: {}", mtSessionId);

            iItemMapper.truncateItem();
            //item插入数据库
            JSONArray itemList = data.getJSONArray("itemList");
            if (itemList != null && !itemList.isEmpty()) {
                for (Object obj : itemList) {
                    JSONObject item = (JSONObject) obj;
                    IItem iItem = new IItem(item);
                    iItemMapper.insert(iItem);
                }
                logger.info("商品列表更新完成，共{}个商品", itemList.size());
            }
        } catch (Exception e) {
            logger.error("获取SessionId失败", e);
            throw new ServiceException("获取SessionId失败: " + e.getMessage());
        }

        return mtSessionId;
    }

    @Override
    public void refreshItem() {
        redisCache.deleteObject("mt_session_id");
        getCurrentSessionId();
    }

    @Override
    public IShop selectByIShopId(String iShopId) {
        if (StringUtils.isEmpty(iShopId)) {
            logger.warn("查询门店时shopId为空");
            return null;
        }
        List<IShop> iShopList = iShopMapper.selectList("i_shop_id", iShopId);
        if (iShopList != null && !iShopList.isEmpty()) {
            return iShopList.get(0);
        }
        logger.debug("未找到门店，shopId: {}", iShopId);
        return null;
    }

    @Override
    public List<IMTItemInfo> getShopsByProvince(String province, String itemId) {
        String key = "mt_province:" + province + "." + getCurrentSessionId() + "." + itemId;
        List<IMTItemInfo> cacheList = redisCache.getCacheList(key);
        if (cacheList != null && cacheList.size() > 0) {
            return cacheList;
        } else {
            List<IMTItemInfo> imtItemInfoList = reGetShopsByProvince(province, itemId);
            redisCache.reSetCacheList(key, imtItemInfoList);
            redisCache.expire(key, 60, TimeUnit.MINUTES);
            return imtItemInfoList;
        }
    }

    public List<IMTItemInfo> reGetShopsByProvince(String province, String itemId) {
        if (StringUtils.isEmpty(province) || StringUtils.isEmpty(itemId)) {
            logger.warn("查询门店时参数为空，province: {}, itemId: {}", province, itemId);
            return new ArrayList<>();
        }

        try {
            long dayTime = LocalDate.now().atStartOfDay().toInstant(ZoneOffset.of("+8")).toEpochMilli();
            String sessionId = getCurrentSessionId();
            String url = "https://static.moutai519.com.cn/mt-backend/xhr/front/mall/shop/list/slim/v3/" 
                + sessionId + "/" + province + "/" + itemId + "/" + dayTime;

            logger.debug("查询省市门店，province: {}, itemId: {}", province, itemId);
            String urlRes = HttpUtil.get(url);
            
            if (StringUtils.isEmpty(urlRes)) {
                throw new ServiceException("查询门店数据失败，响应为空");
            }
            
            JSONObject res = JSONObject.parseObject(urlRes);

            String code = res.getString("code");
            if (StringUtils.isEmpty(code) || !"2000".equals(code)) {
                String message = res.getString("message");
                logger.error("查询门店失败，province: {}, itemId: {}, response: {}", province, itemId, urlRes);
                throw new ServiceException(StringUtils.isNotEmpty(message) ? message : "查询门店失败");
            }
            
            //组合信息
            List<IMTItemInfo> imtItemInfoList = new ArrayList<>();
            JSONObject data = res.getJSONObject("data");
            if (data == null || !data.containsKey("shops")) {
                logger.warn("门店数据为空，province: {}, itemId: {}", province, itemId);
                return imtItemInfoList;
            }
            
            JSONArray shopList = data.getJSONArray("shops");
            if (shopList == null || shopList.isEmpty()) {
                logger.debug("门店列表为空，province: {}, itemId: {}", province, itemId);
                return imtItemInfoList;
            }

            for (Object obj : shopList) {
                JSONObject shops = (JSONObject) obj;
                JSONArray items = shops.getJSONArray("items");
                if (items == null || items.isEmpty()) {
                    continue;
                }
                
                for (Object item : items) {
                    JSONObject itemObj = (JSONObject) item;
                    if (itemId.equals(itemObj.getString("itemId"))) {
                        IMTItemInfo iItem = new IMTItemInfo(shops.getString("shopId"),
                                itemObj.getIntValue("count"), itemObj.getString("itemId"), 
                                itemObj.getIntValue("inventory"));
                        imtItemInfoList.add(iItem);
                    }
                }
            }
            
            logger.info("查询门店完成，province: {}, itemId: {}, 门店数: {}", province, itemId, imtItemInfoList.size());
            return imtItemInfoList;
        } catch (JSONException e) {
            logger.error("解析门店数据JSON失败，province: {}, itemId: {}", province, itemId, e);
            throw new ServiceException("解析门店数据失败: " + e.getMessage());
        } catch (Exception e) {
            logger.error("查询门店异常，province: {}, itemId: {}", province, itemId, e);
            throw new ServiceException("查询门店失败: " + e.getMessage());
        }
    }

    @Override
    public String getShopId(int shopType, String itemId, String province, String city, String lat, String lng) {
        //查询所在省市的投放产品和数量
        List<IMTItemInfo> shopList = getShopsByProvince(province, itemId);
        //取id集合
        List<String> shopIdList = shopList.stream().map(IMTItemInfo::getShopId).collect(Collectors.toList());
        //获取门店列表
        List<IShop> iShops = selectShopList();
        //获取今日的门店信息列表
        List<IShop> list = iShops.stream().filter(i -> shopIdList.contains(i.getIShopId())).collect(Collectors.toList());

        String shopId = "";
        if (shopType == 1) {
            //预约本市出货量最大的门店
            shopId = getMaxInventoryShopId(shopList, list, city);
            if (StringUtils.isEmpty(shopId)) {
                //本市没有则预约本省最近的
                shopId = getMinDistanceShopId(list, province, lat, lng);
            }
        } else {
            //预约本省距离最近的门店
            shopId = getMinDistanceShopId(list, province, lat, lng);
        }

//        if (shopType == 2) {
//            // 预约本省距离最近的门店
//            shopId = getMinDistanceShopId(list, province, lat, lng);
//        }

        if (StringUtils.isEmpty(shopId)) {
            throw new ServiceException("申购时根据类型获取的门店商品id为空");
        }


        return shopId;
    }

    /**
     * 预约本市出货量最大的门店
     *
     * @param list1
     * @param list2
     * @param city
     * @return
     */
    public String getMaxInventoryShopId(List<IMTItemInfo> list1, List<IShop> list2, String city) {
        if (list1 == null || list1.isEmpty() || list2 == null || list2.isEmpty()) {
            logger.warn("门店或商品信息列表为空，无法获取最大库存门店");
            return null;
        }
        
        if (StringUtils.isEmpty(city)) {
            logger.warn("城市名称为空，无法获取最大库存门店");
            return null;
        }

        //本城市的shopId集合
        List<String> cityShopIdList = list2.stream()
                .filter(iShop -> iShop.getCityName() != null && iShop.getCityName().contains(city))
                .map(IShop::getIShopId)
                .collect(Collectors.toList());
        
        if (cityShopIdList.isEmpty()) {
            logger.debug("本城市门店列表为空，city: {}", city);
            return null;
        }

        List<IMTItemInfo> collect = list1.stream()
                .filter(i -> cityShopIdList.contains(i.getShopId()))
                .sorted(Comparator.comparing(IMTItemInfo::getInventory).reversed())
                .collect(Collectors.toList());

        if (collect.isEmpty()) {
            logger.debug("本城市商品信息列表为空，city: {}", city);
            return null;
        }

        return collect.get(0).getShopId();
    }

    /**
     * 预约本省距离最近的门店
     *
     * @param list2
     * @param province
     * @param lat
     * @param lng
     * @return
     */
    public String getMinDistanceShopId(List<IShop> list2, String province, String lat, String lng) {
        if (list2 == null || list2.isEmpty()) {
            logger.warn("门店列表为空，无法计算最近距离");
            return null;
        }
        
        //本省的
        List<IShop> iShopList = list2.stream()
                .filter(iShop -> iShop.getProvinceName() != null && iShop.getProvinceName().contains(province))
                .collect(Collectors.toList());
        
        if (iShopList.isEmpty()) {
            logger.warn("本省门店列表为空，province: {}", province);
            return null;
        }

        try {
            MapPoint myPoint = new MapPoint(Double.parseDouble(lat), Double.parseDouble(lng));
            for (IShop iShop : iShopList) {
                if (StringUtils.isEmpty(iShop.getLat()) || StringUtils.isEmpty(iShop.getLng())) {
                    continue;
                }
                MapPoint point = new MapPoint(Double.parseDouble(iShop.getLat()), Double.parseDouble(iShop.getLng()));
                Double distance = getDisdance(myPoint, point);
                iShop.setDistance(distance);
            }

            List<IShop> collect = iShopList.stream()
                    .filter(shop -> shop.getDistance() != null)
                    .sorted(Comparator.comparing(IShop::getDistance))
                    .collect(Collectors.toList());

            if (collect.isEmpty()) {
                logger.warn("无法计算距离的门店列表为空");
                return null;
            }

            return collect.get(0).getIShopId();
        } catch (NumberFormatException e) {
            logger.error("解析经纬度失败，lat: {}, lng: {}", lat, lng, e);
            throw new ServiceException("经纬度格式错误");
        }
    }

    public static Double getDisdance(MapPoint point1, MapPoint point2) {
        double lat1 = (point1.getLatitude() * Math.PI) / 180; //将角度换算为弧度
        double lat2 = (point2.getLatitude() * Math.PI) / 180; //将角度换算为弧度
        double latDifference = lat1 - lat2;
        double lonDifference = (point1.getLongitude() * Math.PI) / 180 - (point2.getLongitude() * Math.PI) / 180;
        //计算两点之间距离   6378137.0 取自WGS84标准参考椭球中的地球长半径(单位:m)
        return 2 * Math.asin(Math.sqrt(Math.pow(Math.sin(latDifference / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(lonDifference / 2), 2))) * 6378137.0;
    }
}
