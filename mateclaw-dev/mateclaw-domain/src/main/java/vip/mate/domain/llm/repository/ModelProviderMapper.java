package vip.mate.domain.llm.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.domain.llm.model.ModelProviderEntity;

@Mapper
public interface ModelProviderMapper extends BaseMapper<ModelProviderEntity> {
}
