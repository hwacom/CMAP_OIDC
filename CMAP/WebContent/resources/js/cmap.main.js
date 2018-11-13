/**
 * 
 */
feather.replace();

var _ctx = $("meta[name='ctx']").attr("content");
var resutTable;					//DataTable
var navAndMenuAndFooterHeight;	//導覽列+Mobile選單+Footer區塊高度
var deductHeight;				//額外扣除高度 for DataTable資料呈顯區塊高度
var dataTableHeight;			//DataTable資料呈顯區塊高度
var isWEB = true;

/**********************************************************************************************************
 *** 頁面loading完成後各種init項目
 **********************************************************************************************************/
$(document).ready(function() {
	window.sessionStorage.clear();
			
	// get current URL path and assign 'active' class
	var pathname = window.location.pathname;
	
	if (pathname == '/CMAP/' || pathname == '/CMAP/index') {
		$('.nav > .nav-item > a[href="/CMAP/version/manage"]').addClass('active');
	} else {
		$('.nav > .nav-item > a[href="'+pathname+'"]').addClass('active');
	}
	
	if ($('.mobile-menu').is(':visible')) {
		isWEB = false;
	}
	
	$('.toggleMenuLink').click(function() {
		var itemId = '#'+this.id+'_items';
		var iconId = '#'+this.id+' > span > svg.feather-';
		
		$(itemId).toggleClass('collapse');
		
		if ($(itemId).hasClass('collapse')) {
			iconId = iconId+'chevron-up';
			$(iconId).replaceWith(feather.icons['chevron-down'].toSvg());
		} else {
			iconId = iconId+'chevron-down';
			$(iconId).replaceWith(feather.icons['chevron-up'].toSvg());
		}
	});
	
	//計算DataTable區塊高度
	calHeight();

	//縮放視窗大小時，延遲1秒後重算DataTable區塊高度 & 重繪DataTable
    $(window).resize(_.debounce(function() {
    	if (typeof resutTable !== "undefined") {
    		calHeight();
    		$('.dataTables_scrollBody').css('max-height', dataTableHeight);
			resutTable.ajax.reload();
			
			if (typeof $("#checkAll") !== "undefined") {
				$('input[name=checkAll]').prop('checked', false);
			}
			
			if (typeof initActionBar === 'function') {
				initActionBar();
			}
    	}
    	if (typeof resutTable_errorLog !== "undefined") {
    		calHeight();
    		$('.dataTables_scrollBody').css('max-height', dataTableHeight);
    		resutTable_errorLog.ajax.reload();
			
			if (typeof $("#checkAll") !== "undefined") {
				$('input[name=checkAll]').prop('checked', false);
			}
			
			if (typeof initActionBar === 'function') {
				initActionBar();
			}
    	}
    	if (typeof resutTable_jobLog !== "undefined") {
    		calHeight();
    		$('.dataTables_scrollBody').css('max-height', dataTableHeight);
    		resutTable_jobLog.ajax.reload();
			
			if (typeof $("#checkAll") !== "undefined") {
				$('input[name=checkAll]').prop('checked', false);
			}
			
			if (typeof initActionBar === 'function') {
				initActionBar();
			}
    	}
    	
    	if ($('#chkbox').length > 0) {
    		var chooseIdx = [];
    		$.each($('input[name=chkbox]:checked'), function(key, input) {
				chooseIdx.push(input.value);
			});
    		
    		//將使用者已勾選的項目記錄下來
    		window.sessionStorage.setItem(_DATATABLE_CHECKED_ITEM_, JSON.stringify(chooseIdx));
    	}
    	
    	/*
    	$.each( $('.modal:visible'), function( key, value ) {
    		$("#" + value.id).modal('hide');
    	});
    	*/
    	
    }, 1000));
	
  	//全選 or 取消全選
    $('#checkAll').click(function (e) {
    	if ($(this).is(':checked')) {
			$('input[name=chkbox]').prop('checked', true);
			$('tbody > tr').addClass('mySelected');
			
		} else {
			$('input[name=chkbox]').prop('checked', false);
			$('tbody > tr').removeClass('mySelected');
		}
    });
  
  	//查詢按鈕(Web)點擊事件
    $('#btnSearch_web').click(function (e) {
    	findData('WEB');
    });
  	
  	//查詢按鈕(Mobile)點擊事件
    $('#btnSearch_mobile').click(function (e) {
    	findData('MOBILE');
    });
  	
    //[組態檔內容-Modal] >> 開啟時將卷軸置頂
    $("#viewModal").on("shown.bs.modal", function () {
    	setTimeout(function() {
    		$('#viewModal_content').scrollTop(0);
    	}, 30);
    });
    
    $('[data-toggle="tooltip"]').tooltip({
    	'placement': 'top',
    	'html': true
    });
    
    $(".dataTable thead td").click(function() {
    	bindTrEvent();
    });
});

function initCheckedItems() {
	var checkedItem = JSON.parse(window.sessionStorage.getItem(_DATATABLE_CHECKED_ITEM_));
	if (checkedItem == null) {
		return;
	}
	
	if (checkedItem.length > 0) {
		$.each(checkedItem, function(key, value) {
			$('input[name=chkbox][value='+value+']').trigger('click');
		});
	}
}

/**********************************************************************************************************
 *** 將TABLE查詢結果，各資料內容TR加上click事件 (點擊該列資料任一位置時將該筆資料checkbox勾選)
 **********************************************************************************************************/
function bindTrEvent() {
	$('.dataTable tbody tr').click(function(event) {
        if (event.target.tagName !== 'A' && event.target.type !== 'checkbox' && event.target.type !== 'radio') {
          $(':checkbox', this).trigger('click');
          $(':radio', this).prop('checked', ($(':radio', this).is(':checked') ? false : true));
          
          if ($('#chkbox',this).attr('type') === 'radio') {
        	  changeTrBgColor($('#chkbox',this).get(0));
          }
        }
    });
}

/**********************************************************************************************************
 *** 用來將TABLE內容，所有項目checkbox(含checkAll本身)取消勾選
 **********************************************************************************************************/
function uncheckAll() {
	$('input[name=checkAll]').prop('checked', false);
	$('input[name=chkbox]').prop('checked', false);
	$('tbody > tr').removeClass('mySelected');
	
}

/**********************************************************************************************************
 *** 將Modal視窗內，各種輸入欄位內容清空 & 選單初始化(預設為將選項調整為第一個項目) & disabled欄位狀態解除
 **********************************************************************************************************/
function initModal() {
	$("input[name^=input]").val('');
	$("textarea[name^=input]").val('');
	$("select").prop("selectedIndex", 0);
	$(".modal").find(':disabled').attr('disabled', false);
	$("div[id^=sec_]").hide();
}

/**********************************************************************************************************
 *** 隱藏 or 顯示左側功能選單
 **********************************************************************************************************/
function toggleMenu() {
	if (isWEB) {
		$('.web-menu').toggleClass('d-md-block');
		$('main').toggleClass('col-md-10').toggleClass('col-md-12');
		$('footer').toggleClass('col-md-10').toggleClass('col-md-12');
		
	} else {
		$('.mobile-menu').toggleClass('d-none');
		$('#search-bar-small-btn').toggleClass('search-bar-small-btn');
	}
	
	changeMenuIconColor();
	
	//計算DataTable區塊高度
	calHeight();
	$(window).trigger('resize');
}

/**********************************************************************************************************
 *** 改變當前進入的功能，左側選單項目icon的呈顯style
 **********************************************************************************************************/
function changeMenuIconColor() {
	$("#menu-icon").toggleClass('icon-hightlight');
}

/**********************************************************************************************************
 *** 勾選項目時調整該列資料<TR>底色
 **********************************************************************************************************/
function changeTrBgColor(obj) {
	setTimeout(function() {
		var tr = obj.parentNode.parentNode;
		var jObj = $(obj);
		
		if (obj.checked) {
			tr.classList.add('mySelected');
		} else {
			tr.classList.remove('mySelected');
		}
	}, 100);
}

/**********************************************************************************************************
 *** 計算DataTable可呈顯的區塊高度
 **********************************************************************************************************/
function calHeight() {
	//計算dataTable區塊高度，以window高度扣除navbar、footer和mobile版選單高度
	if ($('.mobile-menu').css('display') != 'none') {
		navAndMenuAndFooterHeight = $('.navbar').outerHeight() + $(".footer").outerHeight() + $('.mobile-menu').outerHeight();
	} else {
		navAndMenuAndFooterHeight = $('.navbar').outerHeight() + $(".footer").outerHeight();
	}
	
	if ($('.search-bar-large').css('display') != 'none') {
		navAndMenuAndFooterHeight += $('.search-bar-large').outerHeight();
		deductHeight = 160;
	} else {
		deductHeight = 250;
	}
	
	dataTableHeight = (window.innerHeight-navAndMenuAndFooterHeight-deductHeight);
	
	//避免手機裝置橫向狀態下高度縮太小無法閱讀資料，設定最小高度限制為165px
	dataTableHeight = dataTableHeight < 165 ? 165 : dataTableHeight;
}

/**********************************************************************************************************
 *** 群組選單連動設備選單
 **********************************************************************************************************/
function changeDeviceMenu(deviceMenuObjId, groupId) {
	$( "select[id^='"+deviceMenuObjId+"'] option" ).remove();
	$( "select[id^='"+deviceMenuObjId+"']" ).append("<option value=''>=== ALL ===</option>");
	
	$.ajax({
		url  : _ctx + '/base/getDeviceMenu',
		data : {
			groupId: groupId
		},
		type : "POST",
		dataType : 'json',
		async: false,
		success : function(resp) {

			if (resp.code == '200') {
				var obj = $.parseJSON(resp.data.device);
				$.each(obj, function(key, value){
					$( "select[id^='"+deviceMenuObjId+"']" ).append("<option value='"+key+"'>"+value+"</option>");
				});
			}
		},
		error : function(xhr, ajaxOptions, thrownError) {
			ajaxErrorHandler();
		}
	});
}

/**********************************************************************************************************
 *** 查看組態檔內容
 **********************************************************************************************************/
function viewConfig(viewConfig) {
	var obj = new Object();
	obj.name = 'versionId';
	obj.value = viewConfig;
	
	$.ajax({
		url : _ctx + '/version/view',
		data : JSON.stringify(obj),
		headers: {
		    'Accept': 'application/json',
		    'Content-Type': 'application/json'
		},
		type : "POST",
		async: false,
		success : function(resp) {
			if (resp.code == '200') {
				$('#viewModal_group').val(resp.data.group);
				$('#viewModal_device').val(resp.data.device);
				$('#viewModal_version').val(resp.data.version);
				$('#viewModal_content').html(resp.data.content);
				
				$('#viewModal').modal('show');
				
			} else {
				alert(resp.message);
			}
		},
		error : function(xhr, ajaxOptions, thrownError) {
			ajaxErrorHandler();
		}
	});
}

/**********************************************************************************************************
 *** AJAX 回應異常時處理動作
 **********************************************************************************************************/
function ajaxErrorHandler() {
	alert('連線逾時，頁面將重新導向');
	location.reload();
}

$.fn.serializeObject = function()
{
    var o = {};
    var a = this.serializeArray();
    $.each(a, function() {
        if (o[this.name] !== undefined) {
            if (!o[this.name].push) {
                o[this.name] = [o[this.name]];
            }
            o[this.name].push(this.value || '');
        } else {
            o[this.name] = this.value || '';
        }
    });
    return o;
};

/**********************************************************************************************************
 *** 查詢結果TABLE資料內容處理: 切換欄位顯示部分或全部內容 & 改變滑鼠hover時的鼠標樣式
 **********************************************************************************************************/
function changeShowContent(obj, maxLength){						//obj是td
   var content = $(obj).attr("content");
   if (content != null && content != '') {
      if ($(obj).attr("isDetail") == 'true') {			//若當前是顯示全部，則切換成顯示部分
         $(obj).attr('isDetail', false);
         $(obj).html(getPartialContentHtml(content, maxLength));
         $(obj).switchClass("cursor_zoom_out", "cursor_zoom_in");
      } else {											//若當前是顯示部分，則切換成顯示全部
         $(obj).attr('isDetail', true);
         $(obj).html(getTotalContentHtml(content));
         $(obj).switchClass("cursor_zoom_in", "cursor_zoom_out");
      }
   }
   
   $.fn.dataTable.tables( { visible: true, api: true } ).columns.adjust(); //調整dataTable欄位寬度
}

/**********************************************************************************************************
 *** 查詢結果TABLE資料內容處理: 依照各頁面設定的呈顯內容字數長度，超過的部分則遮蔽顯示「...(顯示)」
 **********************************************************************************************************/
function getPartialContentHtml(content, maxLength) {
	return content.substr(0, maxLength) + '&nbsp;&nbsp;<a href="javascript:void(0);" >...(顯示)</a>';
}

/**********************************************************************************************************
 *** 查詢結果TABLE資料內容處理: 遮蔽的內容，點擊「...(顯示)」後呈顯全部內容
 **********************************************************************************************************/
function getTotalContentHtml(content) {
	return content + '&nbsp;&nbsp;<a href="javascript:void(0);" >(隱藏)</a>';
}