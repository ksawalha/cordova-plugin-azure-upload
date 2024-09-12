var exec = require('cordova/exec');

exports.uploadFiles = function (postId, sasToken, files, successCallback, errorCallback) {
    exec(successCallback, errorCallback, "AzureUpload", "uploadFiles", [postId, sasToken, files]);
};
