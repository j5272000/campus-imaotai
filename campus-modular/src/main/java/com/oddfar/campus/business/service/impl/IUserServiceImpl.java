package com.oddfar.campus.business.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson2.JSONObject;
import com.oddfar.campus.business.entity.IUser;
import com.oddfar.campus.business.mapper.IUserMapper;
import com.oddfar.campus.business.service.IUserService;
import com.oddfar.campus.common.domain.PageResult;
import com.oddfar.campus.common.exception.ServiceException;
import com.oddfar.campus.common.utils.SecurityUtils;
import com.oddfar.campus.common.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class IUserServiceImpl implements IUserService {
    private static final Logger logger = LoggerFactory.getLogger(IUserServiceImpl.class);
    
    @Autowired
    private IUserMapper iUserMapper;

    @Override
    public PageResult<IUser> page(IUser iUser) {
        Long userId = SecurityUtils.getUserId();
        if (userId != 1) {
            return iUserMapper.selectPage(iUser, userId);
        }
        return iUserMapper.selectPage(iUser);
    }

    @Override
    public int insertIUser(Long mobile, String deviceId, JSONObject jsonObject) {
        if (mobile == null) {
            throw new ServiceException("手机号不能为空");
        }
        
        if (jsonObject == null) {
            throw new ServiceException("用户信息不能为空");
        }
        
        JSONObject data = jsonObject.getJSONObject("data");
        if (data == null) {
            throw new ServiceException("用户数据为空");
        }

        IUser user = iUserMapper.selectById(mobile);

        if (user != null) {
            //存在则更新
            IUser iUser = new IUser(mobile, jsonObject);
            iUser.setCreateUser(SecurityUtils.getUserId());
            BeanUtil.copyProperties(iUser, user, "shopType", "minute");
            int result = iUserMapper.updateById(user);
            logger.debug("更新用户信息成功，mobile: {}", mobile);
            return result;
        } else {
            if (StringUtils.isEmpty(deviceId)) {
                deviceId = UUID.randomUUID().toString().toLowerCase();
                logger.debug("生成新设备ID，mobile: {}, deviceId: {}", mobile, deviceId);
            }
            IUser iUser = new IUser(mobile, deviceId, jsonObject);
            iUser.setCreateUser(SecurityUtils.getUserId());
            int result = iUserMapper.insert(iUser);
            logger.info("新增用户成功，mobile: {}", mobile);
            return result;
        }
    }

    @Override
    public List<IUser> selectReservationUser() {
        return iUserMapper.selectReservationUser();

    }

    @Override
    public List<IUser> selectReservationUserByMinute(int minute) {
        return iUserMapper.selectReservationUserByMinute(minute);
    }

    @Override
    public int insertIUser(IUser iUser) {
        if (iUser == null || iUser.getMobile() == null) {
            throw new ServiceException("用户信息不能为空");
        }

        IUser user = iUserMapper.selectById(iUser.getMobile());
        if (user != null) {
            throw new ServiceException("禁止重复添加，该手机号已存在");
        }

        if (StringUtils.isEmpty(iUser.getDeviceId())) {
            iUser.setDeviceId(UUID.randomUUID().toString().toLowerCase());
        }
        iUser.setCreateUser(SecurityUtils.getUserId());
        return iUserMapper.insert(iUser);
    }

    @Override
    public int updateIUser(IUser iUser) {
        if (iUser == null) {
            throw new ServiceException("用户信息不能为空");
        }
        
        if (iUser.getMobile() == null) {
            throw new ServiceException("手机号不能为空");
        }
        
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null) {
            throw new ServiceException("当前用户ID为空");
        }
        
        if (currentUserId != 1 && (iUser.getCreateUser() == null || !iUser.getCreateUser().equals(currentUserId))) {
            throw new ServiceException("只能修改自己创建的用户");
        }
        
        int result = iUserMapper.updateById(iUser);
        logger.debug("更新用户信息成功，mobile: {}", iUser.getMobile());
        return result;
    }

    @Override
    @Async
    public void updateUserMinuteBatch() {
        try {
            Long userCount = iUserMapper.selectCount();
            if (userCount == null || userCount == 0) {
                logger.debug("用户数量为0，跳过批量更新预约时间");
                return;
            }
            
            logger.info("开始批量更新用户预约时间，用户数量: {}", userCount);
            if (userCount > 60) {
                iUserMapper.updateUserMinuteEven();
                logger.info("使用均匀分配方式更新预约时间完成");
            } else {
                iUserMapper.updateUserMinuteBatch();
                logger.info("使用批量方式更新预约时间完成");
            }
        } catch (Exception e) {
            logger.error("批量更新用户预约时间失败", e);
            throw new ServiceException("批量更新用户预约时间失败: " + e.getMessage());
        }
    }

    @Override
    public int deleteIUser(Long[] iUserId) {
        return iUserMapper.deleteIUser(iUserId);
    }
}
