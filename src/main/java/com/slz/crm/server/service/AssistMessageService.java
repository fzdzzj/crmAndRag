package com.slz.crm.server.service;

import com.slz.crm.pojo.vo.AssistMessageVO;
import java.util.List;

/** 协助生命周期内的文本沟通与系统审计消息。 */
public interface AssistMessageService {
  List<AssistMessageVO> listVisible(Long assistId);

  void sendText(Long assistId, String content);

  void appendSystemMessage(Long assistId, String content);
}
