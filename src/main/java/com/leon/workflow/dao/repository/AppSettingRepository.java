package com.leon.workflow.dao.repository;

import com.leon.workflow.dao.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
