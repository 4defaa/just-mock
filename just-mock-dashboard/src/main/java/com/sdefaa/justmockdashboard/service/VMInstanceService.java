package com.sdefaa.justmockdashboard.service;

import com.sdefaa.justmockdashboard.pojo.dto.VMInstanceDTO;

import java.util.List;

/**
 * @author Julius Wong
 * <p>
 * 虚拟机实例业务层
 * <p>
 * @since 1.0.0
 */
public interface VMInstanceService {
   List<VMInstanceDTO> getAllVMInstances();
}
