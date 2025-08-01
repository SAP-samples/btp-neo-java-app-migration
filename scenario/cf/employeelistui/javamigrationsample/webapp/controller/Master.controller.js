sap.ui.define([
	"sap/ui/core/mvc/Controller",
	"javamigrationsample/model/models",
	"sap/ui/model/json/JSONModel",
	"sap/m/MessageBox"
], function (Controller, models, JSONModel, MessageBox) {
	"use strict";

	return Controller.extend("javamigrationsample.controller.Master", {
		onInit: function () {
			var oViewModel = new JSONModel({
				fileTypes: ['jpg', 'png']
			});
			this.getView().setModel(oViewModel, "viewModel");

			models.createCurrentUserModel().then(function (oModel) {
				this.getView().setModel(oModel, "userInfoModel");
			}.bind(this));

			this.busyToggle(true);
			models.createEmployeeModel().then(function (oModel) {
					this.getView().setModel(oModel, "employeeModel");
					this.busyToggle(false);
				}.bind(this))
				.catch(function () {
					this._handleError();
				}.bind(this));
		},
		busyToggle: function (bBusy) {
			this.getView().byId("idEmployeeTable").setBusy(bBusy);
		},
		onAddEmployeePress: function (oEvent) {
			this.pDialog = this.loadFragment({
				name: "javamigrationsample.fragments.AddEmployee"
			});

			this.pDialog.then(function (oDialog) {
				this.getView().setModel(models.createNewEmployeeModel(), "dialogModel");
				this.getView().addDependent(oDialog);
				oDialog.open();
			}.bind(this));
		},

		onDeleteEmployeePress: function (oEvent) {
			var oContext = oEvent.getSource().getBindingContext("employeeModel");
			var sId = oContext.getProperty("id");
			this.busyToggle(true);
			models.deleteEmployee(sId).then(function () {
					models.refreshEmployeeModel();
				}.bind(this))
				.catch(function () {
					this._handleError();
				}.bind(this))
				.finally(function () {
					this.busyToggle(false);
				}.bind(this));
		},

		onFileChange: function (oEvent) {
			var file = oEvent.getParameter("files")[0];
			this.getView().getModel("dialogModel").setProperty("/avatar", file);
			this.getView().getModel("dialogModel").setProperty("/avatarBase64", window.URL.createObjectURL(file));
		},
		onAddEmpoyeeDialog: function (oEvent) {
			var oNewEmpl = this.getView().getModel("dialogModel").getData();
			var oDialog = this.getView().byId("addEmployeeId");
			oDialog.setBusy(true);
			models.addEmployee(oNewEmpl).then(function () {
					this._closeAddEmployeeDialog();
					models.refreshEmployeeModel();
				}.bind(this))
				.catch(function () {
					this._handleError();
				}.bind(this))
				.finally(function () {
					oDialog.setBusy(false);
				});
		},
		onCloseDialog: function (oEvent) {
			this._closeAddEmployeeDialog();
		},

		formatAvatar: function (imageId) {
			var sRelativeAppPath = sap.ui.require.toUrl("javamigrationsample");
			var sUrl = "";
			if (imageId) {
				sUrl = sRelativeAppPath + "/java/images?documentId=" + imageId;
			} else {
				sUrl = "sap-icon://customer";
			}
			return sUrl;
		},
		
		formatCurrentUser: function (userData, label) {
			var sText = label;
			if (userData) {
				sText = sText + " " + userData.username + "-" + userData.email;
			}
			return sText;
		},
		
		_closeAddEmployeeDialog: function () {
			this.pDialog.then(function (oDialog) {
				oDialog.close();
				oDialog.destroy();
			});
		},

		_handleError: function () {
			MessageBox.error("Something went wrong");
		}
	});
});