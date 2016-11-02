package com.plateno.booking.internal.job.order.abnormalSweepJob.service;


import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import com.plateno.booking.internal.base.mapper.OrderMapper;
import com.plateno.booking.internal.base.mapper.OrderPayLogMapper;
import com.plateno.booking.internal.base.mapper.OrderProductMapper;
import com.plateno.booking.internal.base.mapper.SmsLogMapper;
import com.plateno.booking.internal.base.pojo.Order;
import com.plateno.booking.internal.base.pojo.OrderPayLog;
import com.plateno.booking.internal.base.pojo.OrderPayLogExample;
import com.plateno.booking.internal.base.pojo.OrderProduct;
import com.plateno.booking.internal.base.pojo.OrderProductExample;
import com.plateno.booking.internal.base.pojo.SmsLog;
import com.plateno.booking.internal.bean.config.Config;
import com.plateno.booking.internal.bean.contants.BookingConstants;
import com.plateno.booking.internal.bean.contants.BookingResultCodeContants;
import com.plateno.booking.internal.bean.contants.PayGateCode;
import com.plateno.booking.internal.bean.contants.ViewStatusEnum;
import com.plateno.booking.internal.bean.request.point.ValueBean;
import com.plateno.booking.internal.bean.response.gateway.pay.PayQueryResponse;
import com.plateno.booking.internal.bean.response.gateway.refund.RefundQueryResponse;
import com.plateno.booking.internal.common.util.LogUtils;
import com.plateno.booking.internal.gateway.PaymentService;
import com.plateno.booking.internal.goods.MallGoodsService;
import com.plateno.booking.internal.member.PointService;
import com.plateno.booking.internal.service.log.OrderLogService;
import com.plateno.booking.internal.service.order.MOrderService;
import com.plateno.booking.internal.sms.SMSSendService;
import com.plateno.booking.internal.sms.model.SmsMessageReq;

@Service
public class MallExceptionFlowService {
	protected final static Logger logger = LoggerFactory.getLogger(MallExceptionFlowService.class);
	

	@Autowired
	private OrderLogService orderLogService;

	@Autowired
	private PaymentService paymentService;
	
	
	@Autowired
	private OrderMapper orderMapper;
	
	@Autowired 
	private MOrderService  orderService;
	
	@Autowired 
	private PointService  pointService;
	
	@Autowired
	private OrderPayLogMapper orderPayLogMapper;
	
	@Autowired
	private MallGoodsService mallGoodsService;
	
	@Autowired
	private OrderProductMapper orderProductMapper;
	
	@Autowired
	private TaskExecutor taskExecutor;
	
	@Autowired
	private SMSSendService sendService;
	
	@Autowired
	private SmsLogMapper smsLogMapper;

	
	@SuppressWarnings("unchecked")
	public void handleException() throws Exception {
		
		//查询已发货的订单，如果大于15天则更新为已收货4==>5
		List<Order> orderEList=orderMapper.getOrderByStatus(BookingResultCodeContants.PAY_STATUS_4, 15);
		for(Order order:orderEList){
			order.setPayStatus(BookingResultCodeContants.PAY_STATUS_5);
			order.setUpTime(new Date());
			orderMapper.updateByPrimaryKeySelective(order);
			orderLogService.saveGSOrderLog(order.getOrderNo(), BookingConstants.PAY_STATUS_5, "已完成", "已完成",0,ViewStatusEnum.VIEW_STATUS_COMPLETE.getCode(),"扫单job维护");
		}
		
		//超过30分钟未支付 ==> 1 --> 2
		List<Order> orderList=orderMapper.getPre30Min(BookingResultCodeContants.PAY_STATUS_1);
		for(Order order:orderList){
			order.setPayStatus(BookingResultCodeContants.PAY_STATUS_2);
			order.setUpTime(new Date());
			orderMapper.updateByPrimaryKeySelective(order);
			
			returnPoint(order);
			
			orderLogService.saveGSOrderLog(order.getOrderNo(), BookingConstants.PAY_STATUS_2, "已取消", "订单取消成功",0,ViewStatusEnum.VIEW_STATUS_CANNEL.getCode(),"扫单job维护");
		}
		
		//退款中的订单
		List<Order> orderTList=orderMapper.getPre30Min(BookingResultCodeContants.PAY_STATUS_10);
		handleEach(orderTList);
		
	
		//支付中的订单

		List<Order> orderPayingList=orderMapper.getPre30Min(BookingResultCodeContants.PAY_STATUS_11);
		handleEach(orderPayingList);
	}
		
	
	private void handleEach(List<Order> listOrder)throws Exception{
		for(Iterator<Order> iter=listOrder.iterator();iter.hasNext();){
			Order order = (Order)iter.next();
			Integer status=order.getPayStatus();
			switch(status){
			
			//处理账单退款中状态：10,验证网关退款查询接口 ==> 7/13
			case 10:
				handleGateWayefund(order);
				break;
			
			//处理支付中账单状态：11,验证网关支付查询接口==>3/12
			case 11:
					handlePaying(order);
				break;
			
			}
		}
	}

	/**
	 * 物理确认订单的最终状态
	 * 
	 * @param mbill
	 * @param status
	 * @return
	 */
	private boolean validate(Order order,Integer status){
		Order mbills= (Order) orderMapper.selectByPrimaryKey(order.getId());
		if(mbills==null) 
			return false;
		if(!mbills.getPayStatus().equals(status)) 
			return false;
		return true;
	}
	

	
	private void handleGateWayefund(Order order)throws Exception{
		if(!validate(order,BookingConstants.PAY_STATUS_10)) 
			return ;
		
		OrderPayLogExample example=new OrderPayLogExample();
		example.createCriteria().andOrderIdEqualTo(order.getId()).andTypeEqualTo(2);
		List<OrderPayLog> listpayLog=orderPayLogMapper.selectByExample(example);
		if(CollectionUtils.isEmpty(listpayLog))		return;
		
		boolean success = false;
		boolean fail = false;
		for(OrderPayLog orderPayLog:listpayLog){
			
			//获取网关的订单状态
			RefundQueryResponse response = paymentService.refundOrderQuery(orderPayLog.getTrandNo());
			if (response == null || StringUtils.isBlank(response.getCode())) {
				logger.error("查询支付网关订单失败, trandNo:" + orderPayLog.getTrandNo());
				return;
			}
			
			if(response.getCode().equals(PayGateCode.HADNLING) || response.getCode().equals(PayGateCode.PAY_HADNLING)) {
				logger.error(String.format("退款支付网关订单支付中, trandNo:s%, code:s%", orderPayLog.getTrandNo(), response.getCode()));
				return;
			}
			
			if(response.getCode().equals(BookingConstants.GATEWAY_REFUND_SUCCESS_CODE)){ //退款成功
				//更新支付流水状态(success == 2)
				OrderPayLog record=new OrderPayLog();
				record.setStatus(BookingConstants.BILL_LOG_SUCCESS);
				orderPayLogMapper.updateByExampleSelective(record, example);
				
				success=true;
			}else if((response.getCode().equals(PayGateCode.REFUND_FAIL) || response.getCode().equals(PayGateCode.REQUEST_EXCEPTION))){ //退款失败
				//更新支付流水状态(fail == 3)
				OrderPayLog record=new OrderPayLog();
				record.setStatus(BookingConstants.BILL_LOG_FAIL);
				orderPayLogMapper.updateByExampleSelective(record, example);
				
				fail = true;
			}
		}
		
		Order record = new Order();
		record.setRefundSuccesstime(new Date());
		String orderNo = order.getOrderNo();
		if(success){
			record.setPayStatus(BookingResultCodeContants.PAY_STATUS_7);
			orderLogService.saveGSOrderLog(orderNo, BookingResultCodeContants.PAY_STATUS_7, "网关退款成功", "网关退款成功",order.getChanelid(),ViewStatusEnum.VIEW_STATUS_REFUND.getCode(),"扫单job维护");
			//更新账单状态
			orderService.updateOrderStatusByNo(record, orderNo);
			
			//退款归还下单积分
			returnPoint(order);
			
			OrderProduct productByOrderNo = getProductByOrderNo(orderNo);
			if(productByOrderNo == null) {
				logger.error(String.format("orderNo:s%, 退款退库存失败, 找不到购买的商品信息", orderNo));
			} else {
				//更新库存
				logger.info(String.format("orderNo:s%， 退还库存，skuid:s%, count:s%", orderNo, productByOrderNo.getSkuid(), productByOrderNo.getSkuCount()));
				boolean modifyStock = mallGoodsService.modifyStock(productByOrderNo.getSkuid().toString(), productByOrderNo.getSkuCount());
				if(!modifyStock){
					logger.error(String.format("orderNo:s%, 调用商品服务失败", orderNo));
					LogUtils.sysLoggerInfo(String.format("orderNo:s%, 调用商品服务失败", orderNo));
				}
				
				final Order dbOrder = order;
				final OrderProduct product = productByOrderNo;
				//发送退款短信
				taskExecutor.execute(new Runnable() {
					@Override
					public void run() {
						
						SmsMessageReq messageReq = new SmsMessageReq();
						Map<String, String> params = new HashMap<String, String>();
						if(dbOrder.getPoint() > 0){
							messageReq.setPhone(dbOrder.getMobile());
							params.put("orderCode", dbOrder.getOrderNo());
							params.put("name", product.getProductName());
							params.put("money", dbOrder.getPayMoney()+"");
							params.put("jf",dbOrder.getPoint()+"");
							messageReq.setType(Integer.parseInt(Config.SMS_SERVICE_TEMPLATE_NINE));
						}else{
							params.remove("jf");
							messageReq.setType(Integer.parseInt(Config.SMS_SERVICE_TEMPLATE_EIGHT));
						}
						Boolean res=sendService.sendMessage(messageReq);
						
						//记录短信日志
						SmsLog smslog=new SmsLog();
						smslog.setCreateTime(new Date());
						smslog.setIsSuccess(res==true?1:0);
						smslog.setContent(product.getProductName());
						smslog.setObjectNo(dbOrder.getOrderNo());
						smslog.setPhone(dbOrder.getMobile());
						smslog.setUpdateTime(new Date());
						smsLogMapper.insertSelective(smslog);
						
					}
				});
			}
		}else if(fail){
			record.setPayStatus(BookingResultCodeContants.PAY_STATUS_13);
			record.setRefundFailReason("网关退款失败");
			orderLogService.saveGSOrderLog(orderNo, BookingConstants.PAY_STATUS_13, "网关退款失败", "网关退款失败",order.getChanelid(),ViewStatusEnum.VIEW_STATUS_REFUND_FAIL.getCode(),"扫单job维护");
			//更新账单状态
			orderService.updateOrderStatusByNo(record, orderNo);
		}
	  }


	/**
	 * 返还积分
	 * @param order
	 */
	private void returnPoint(Order order) {
		if(order.getPoint()>0){
			ValueBean vb=new ValueBean();
			vb.setPointvalue(order.getPoint());
			vb.setMebId(order.getMemberId());
			vb.setTrandNo(order.getOrderNo());
			vb.setChannelid(order.getChanelid());
			pointService.mallAddPoint(vb);
		}
	}
	
	/**
	 * 获取订单的商品信息
	 * @param orderNo
	 * @return
	 */
	public OrderProduct getProductByOrderNo(String orderNo) {
		OrderProductExample orderProductExample=new OrderProductExample();
		orderProductExample.createCriteria().andOrderNoEqualTo(orderNo);
		@SuppressWarnings("unchecked")
		List<OrderProduct> productOrderList = orderProductMapper.selectByExample(orderProductExample);
		if(CollectionUtils.isEmpty(productOrderList)) {
			return null;
		}
		
		return productOrderList.get(0);
	}
	
	
	private void handlePaying(Order order)throws Exception{
		if(!validate(order,BookingConstants.PAY_STATUS_11)) 
			return ;
		
		OrderPayLogExample example=new OrderPayLogExample();
		example.createCriteria().andOrderIdEqualTo(order.getId()).andTypeEqualTo(1);
		List<OrderPayLog> listpayLog=orderPayLogMapper.selectByExample(example);
		if(CollectionUtils.isEmpty(listpayLog))	{
			logger.error("订单状态异常, 订单状态支付中，但是找不到支付流水, orderNo:" + order.getOrderNo());
			return;
		}
		
		boolean success = false;
		for(OrderPayLog orderPayLog:listpayLog){
			
			//获取网关的订单状态
			PayQueryResponse response = paymentService.payOrderQuery(orderPayLog.getTrandNo());
			if (response == null || StringUtils.isBlank(response.getCode())) {
				logger.error("查询支付网关订单失败, trandNo:" + orderPayLog.getTrandNo());
				return;
			}
			
			if(response.getCode().equals(PayGateCode.HADNLING) || response.getCode().equals(PayGateCode.PAY_HADNLING) || response.getCode().equals(PayGateCode.UNKNOWN_STATUS)) {
				logger.error(String.format("支付网关订单不是最终状态, trandNo:s%, code:s%", orderPayLog.getTrandNo(), response.getCode()));
				return;
			}
				
			if(response.getCode().equals(BookingConstants.GATEWAY_PAY_SUCCESS_CODE)){
				//更新支付流水状态(success == 2)
				OrderPayLog record=new OrderPayLog();
				record.setStatus(BookingConstants.BILL_LOG_SUCCESS);
				orderPayLogMapper.updateByExampleSelective(record, example);
				
				success=true;
			}else{
				//更新支付流水状态(fail == 3)
				OrderPayLog record=new OrderPayLog();
				record.setStatus(BookingConstants.BILL_LOG_FAIL);
				orderPayLogMapper.updateByExampleSelective(record, example);
			}
		}
		
		Order record = new Order();
		if(success){
			record.setPayStatus(BookingResultCodeContants.PAY_STATUS_3);
			orderLogService.saveGSOrderLog(order.getOrderNo(), BookingResultCodeContants.PAY_STATUS_3, "网关支付成功", "网关支付成功",order.getChanelid(),ViewStatusEnum.VIEW_STATUS_WATIDELIVER.getCode(),"扫单job维护");
		}else{
			record.setPayStatus(BookingResultCodeContants.PAY_STATUS_12);
			orderLogService.saveGSOrderLog(order.getOrderNo(), BookingConstants.PAY_STATUS_12, "网关支付失败", "网关支付失败",order.getChanelid(),ViewStatusEnum.VIEW_STATUS_PAYFAIL.getCode(),"扫单job维护");
		}
		//更新账单状态
		orderService.updateOrderStatusByNo(record, order.getOrderNo());
	  }
	

}
