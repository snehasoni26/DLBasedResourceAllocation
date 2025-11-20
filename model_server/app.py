from tensorflow.keras.models import load_model
from tensorflow.keras.metrics import MeanSquaredError
import pickle

model = load_model('resource_prediction_model.h5', custom_objects={'mse': MeanSquaredError})

with open('scaler_X.pkl', 'rb') as f:
    scaler_X = pickle.load(f)

with open('scaler_y.pkl', 'rb') as f:
    scaler_y = pickle.load(f)

print("✅ Model and scalers loaded successfully!")

 #------------------------------------------------

from flask import Flask

app = Flask(__name__)

from flask import request, jsonify
import numpy as np

@app.route('/predict', methods=['POST'])
def predict():
    data = request.get_json(force=True)
    # Assuming the input data is a list of features in the correct order
    input_data = np.array([data['features']]) # Wrap in list to handle single prediction


    scaled_input = scaler_X.transform(input_data)
    prediction_scaled = model.predict(scaled_input)
    prediction = scaler_y.inverse_transform(prediction_scaled)


    # Assuming prediction returns a single sample result
    prediction_result = {
        'CPU_utilization': float(prediction[0][0]),
        'Memory_usage': float(prediction[0][1]),
        'Disk_IO_MBps': float(prediction[0][2]),
        'Network_bw_MBps': float(prediction[0][3])
    }

    return jsonify(prediction_result)

if __name__ == '__main__':
    app.run(debug=True)
