package com.plateno.testservice;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import com.plateno.booking.internal.base.model.SelectOrderParam;
import com.plateno.booking.internal.bean.config.Config;
import com.plateno.booking.internal.bean.contants.BookingConstants;
import com.plateno.booking.internal.bean.exception.OrderException;
import com.plateno.booking.internal.bean.request.common.LstOrder;
import com.plateno.booking.internal.bean.request.custom.MOrderParam;
import com.plateno.booking.internal.bean.request.custom.ModifyOrderParams;
import com.plateno.booking.internal.bean.request.custom.ReceiptParam;
import com.plateno.booking.internal.bean.response.custom.OrderDetail;
import com.plateno.booking.internal.bean.response.custom.SelectOrderResponse;
import com.plateno.booking.internal.goods.MallGoodsService;
import com.plateno.booking.internal.interceptor.adam.common.bean.ResultVo;
import com.plateno.booking.internal.service.order.MOrderService;
import com.plateno.booking.internal.sms.SMSSendService;
import com.plateno.booking.internal.sms.model.SmsMessageReq;
import com.plateno.booking.internal.wechat.model.ProductSkuBean;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(locations = "classpath:applicationContext.xml")
public class MOrderServiceTest {
	
	@Autowired
	private MOrderService service;
	
	@Autowired
	private MallGoodsService mallGoodsService;
	
	@Autowired
	private TaskExecutor taskExecutor;
	
	@Autowired
	private SMSSendService sendService;
	
	@Test
	public void testModifyReceiptInfo() throws OrderException, Exception{
		
		ReceiptParam receiptParam = new ReceiptParam();
		receiptParam.setOrderNo("O1474959953609872686");
		receiptParam.setReceiptName("张思");
		receiptParam.setReceiptMobile("13888888888");
		receiptParam.setReceiptAddress("银河系");
		receiptParam.setOperateUserid("123456");
		receiptParam.setOperateUsername("管理员");
		receiptParam.setPlateForm(1);
		
		ResultVo<Object> modifyReceiptInfo = service.modifyReceiptInfo(receiptParam );
		System.out.println("结果：" + modifyReceiptInfo);
	}
	
	@Test
	public void testGetOrderDetail() throws OrderException, Exception{
		
		
		MOrderParam orderParam = new MOrderParam();
		orderParam.setOrderNo("O1478144276890105203");
		ResultVo<OrderDetail> orderDetail = service.getOrderDetail(orderParam );
		System.out.println("结果：" + orderDetail);
	}
	
	@Test
	public void testGetProductAndskuStock() throws OrderException, Exception{
		
		
		ProductSkuBean productAndskuStock = mallGoodsService.getProductAndskuStock("9");
		System.out.println(productAndskuStock);
		
	}
	
	@Test
	public void testQueryOrderByPage() throws OrderException, Exception{
		
		
		SelectOrderParam param = new SelectOrderParam();
		param.setRequstPlatenoform(2);
		param.setOrderNo("O1478499130284514088");
		//param.setMemberId("181295316");
		/*param.setChannelId(1);
		List<Integer> statusList = new ArrayList<>();
		statusList.add(2);
		param.setStatusList(statusList);*/
		//param.setName("四");
		
		ResultVo<LstOrder<SelectOrderResponse>> queryOrderByPage = service.queryOrderByPage(param);
		System.out.println(queryOrderByPage);
		
	}
	
	@Test
	public void testUserRefund() throws OrderException, Exception{
		
		MOrderParam param = new MOrderParam();
		param.setOrderNo("O1478076012273383901");
		ResultVo<Object> userRefund = service.userRefund(param );
		System.out.println(userRefund);
		
	}
	
	@Test
	public void testConsentRefund() throws OrderException, Exception{
		
		MOrderParam param = new MOrderParam();
		param.setOrderNo("O1478076012273383901");
		ResultVo<Object> userRefund = service.refundOrder(param);
		System.out.println(userRefund);
		
	}
	
	@Test
	public void testSms() throws OrderException, Exception{
		
		//发送退款短信
		taskExecutor.execute(new Runnable() {
			@Override
			public void run() {
				
				SmsMessageReq messageReq = new SmsMessageReq();
				Map<String, String> params = new HashMap<String, String>();
				messageReq.setPhone("13533048661");
				params.put("orderCode", "12345678");
				params.put("name", "商品");
				params.put("money", "50000");
				params.put("jf","10");
				messageReq.setType(Integer.parseInt(Config.SMS_SERVICE_TEMPLATE_NINE));
				Boolean res=sendService.sendMessage(messageReq);
				System.out.println(res);
			}
		});
		
	}
	
	
	@Test
	public void testCancelOrder() throws OrderException, Exception{
		
		MOrderParam orderParam = new MOrderParam();
		orderParam.setOrderNo("O1478154702609388278");
		ResultVo<Object> userRefund = service.cancelOrderLock(orderParam);
		System.out.println(userRefund);
		
	}
	
	@Test
	public void testModifyOrder() throws OrderException, Exception{
		ModifyOrderParams modifyOrderParams = new ModifyOrderParams();
		modifyOrderParams.setNewStatus(BookingConstants.PAY_STATUS_6);
		modifyOrderParams.setOrderNo("O1478568730093888087");
		modifyOrderParams.setRemark("测试修改状态");
		modifyOrderParams.setOperateUserid("32323");
		modifyOrderParams.setOperateUsername("xiaoming");
		modifyOrderParams.setPlateForm(1);
		ResultVo<Object> modifyOrder = service.modifyOrder(modifyOrderParams );
		
		if(modifyOrder.success() && modifyOrderParams.getNewStatus() == BookingConstants.PAY_STATUS_6) {
			MOrderParam orderParam = new MOrderParam();
			orderParam.setOrderNo(modifyOrderParams.getOrderNo());
			orderParam.setMemberId((int)modifyOrder.getData());
			orderParam.setRefundRemark("");
			orderParam.setOperateUserid(modifyOrderParams.getOperateUserid());
			orderParam.setOperateUsername(modifyOrderParams.getOperateUsername());
			orderParam.setPlateForm(modifyOrderParams.getPlateForm());
			
			try {
				ResultVo<Object> refundOrder = service.refundOrder(orderParam);
				System.out.println(String.format("orderNo:%s, 执行退款，结果：%s", modifyOrderParams.getOrderNo(), refundOrder));
			} catch (Throwable e) {
				System.out.println("退款审核失败:" + modifyOrderParams.getOrderNo());
			}
		}
		
		System.out.println(modifyOrder);
	}

}