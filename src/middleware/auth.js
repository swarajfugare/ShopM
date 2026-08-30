const jwt = require('jsonwebtoken');

const JWT_SECRET = process.env.JWT_SECRET || 'matoshree_boutique_secure_jwt_secret_key_2026_x89f';

function authenticateToken(req, res, next) {
  const authHeader = req.headers['authorization'];
  const token = authHeader && authHeader.split(' ')[1];

  if (!token) {
    return res.status(401).json({
      status: 'error',
      message: 'Access denied. No authentication token provided.',
      timestamp: Date.now()
    });
  }

  try {
    const verified = jwt.verify(token, JWT_SECRET);
    req.user = verified;
    next();
  } catch (err) {
    return res.status(401).json({
      status: 'error',
      message: 'Invalid or expired authentication token.',
      timestamp: Date.now()
    });
  }
}

module.exports = {
  authenticateToken,
  JWT_SECRET
};
