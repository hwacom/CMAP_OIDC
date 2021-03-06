<%@ page contentType="text/html; charset=UTF-8" %>
<section>

  <div class="container-fluid">
    <!-- [START]查詢欄位&操作按鈕 for 大型解析度螢幕 -->
	<div class="row search-bar-large">
	  <!-- [START]查詢欄位bar -->
      <div class="col-12 search-bar">
      	<form>
      		<div class="container-fluid">
	      	  <div class="form-group row">
	    	    <div class="col-lg-3 group-field-other">
	    	    	<span class="font-weight-bold" style="width: 30%">腳本類別</span>
	    	    	<select id="queryScriptType" style="width: 65%">
	    	    		<option value="">=== ALL ===</option>
	    	    	</select>
	    	    </div>
				<div class="col-lg-2" style="padding-top: 5px;">
	    	    	<button type="button" class="btn btn-primary btn-sm" style="width: 100%" id="btnSearch">查詢</button>
	    	    </div>
	      	  </div>
	      	</div>
		</form>
      </div>
      <!-- [END]查詢欄位bar -->
      <!-- [START]操作按鈕bar -->
      <div class="col-12 action-btn-bar">
        <div class="container-fluid">
        	<div class="row">
        		<div class="col-lg-2 action-btn-bar-style center">
		  	    	<button type="button" class="btn btn-success btn-sm" style="width: 100%" id="btnCompare">新增腳本</button>
		  	    </div>
		  	    <div class="col-lg-2 action-btn-bar-style center">
		  	    	<button type="button" class="btn btn-info btn-sm" style="width: 100%" id="btnCompare">修改腳本</button>
		  	    </div>
		  	    <div class="col-lg-2 action-btn-bar-style center">
		  	    	<button type="button" class="btn btn-danger btn-sm" style="width: 100%" id="btnCompare">刪除腳本</button>
		  	    </div>
		  	    <div class="center" style="width: 3%">
		  	    	<span style="font-size: 1.5rem">|</span>
		  	    </div>
		  	    <div class="col-lg-2 action-btn-bar-style center">
		  	    	<button type="button" class="btn btn-secondary btn-sm" style="width: 100%" id="btnCompare">變數檔維護</button>
		  	    </div>
		  	    <div class="col-lg-2 action-btn-bar-style center">
		  	    	<button type="button" class="btn btn-secondary btn-sm" style="width: 100%" id="btnCompare">腳本類別維護</button>
		  	    </div>
        	</div>
        </div>
      </div>
      <!-- [END]操作按鈕bar -->
    </div>
    <!-- [END]查詢欄位&操作按鈕 for 大型解析度螢幕 -->
  </div>

</section>

<script src="${pageContext.request.contextPath}/resources/js/cmap.script.main.js"></script>